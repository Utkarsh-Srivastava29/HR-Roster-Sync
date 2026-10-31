package com.moveinsync.roster.service;

import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.reconcile.DiffAction;
import com.moveinsync.roster.core.reconcile.ReconciliationEngine;
import com.moveinsync.roster.core.reconcile.SafetyBrake;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.service.error.BackpressureException;
import com.moveinsync.roster.service.error.ConflictException;
import com.moveinsync.roster.service.error.InvalidRequestException;
import com.moveinsync.roster.service.error.NotFoundException;
import com.moveinsync.roster.service.error.ServiceUnavailableException;
import com.moveinsync.roster.service.model.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Nightly reconciliation: store the uploaded export on disk, diff it in bounded memory, check the
 * safety brake, then either apply every change through the normal version rules or stop and wait
 * for an operator. Runs of one tenant never overlap; tenants run in parallel.
 *
 * <p>Re-running a file is always safe: every change goes through the same idempotent,
 * version-checked path as the live feed, so a crash mid-run is recovered by running it again.
 */
public class ReconciliationService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconciliationService.class);
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    public static final int QUEUE_PER_TENANT = 10;

    private final RosterStore store;
    private final RosterService roster;
    private final CatalogService catalogs;
    private final RunStore runs;
    private final RosterMetrics metrics;
    private final Clock clock;
    private final Path dataDir;
    private volatile SafetyBrake brake;
    private final Map<String, ThreadPoolExecutor> workers = new ConcurrentHashMap<>();

    public ReconciliationService(RosterStore store, RosterService roster, CatalogService catalogs, RunStore runs,
                                 RosterMetrics metrics, Clock clock, Path dataDir, SafetyBrake brake) {
        this.store = store;
        this.roster = roster;
        this.catalogs = catalogs;
        this.runs = runs;
        this.metrics = metrics;
        this.clock = clock;
        this.dataDir = dataDir;
        this.brake = brake;
    }

    public SafetyBrake brake() { return brake; }

    /** Change the threshold at runtime (admin). */
    public void setBrake(SafetyBrake b) { this.brake = b; }

    // ------------------------------------------------------------------ commands

    /** Save the upload and queue the run. Returns at once with status RECEIVED. */
    public ReconciliationRun submit(String tenantId, InputStream upload) {
        String runId = UUID.randomUUID().toString();
        Path file = exportFile(tenantId, runId);
        long bytes;
        try {
            Files.createDirectories(file.getParent());
            bytes = copyLimited(upload, file);
        } catch (IOException e) {
            throw new ServiceUnavailableException("could not store the export file", e);
        }
        ReconciliationRun run = new ReconciliationRun(runId, tenantId, ReconciliationRun.Status.RECEIVED, clock.instant(),
                clock.instant(), bytes, null, null, Map.of(), List.of(), null, null);
        runs.save(run);
        enqueue(tenantId, runId, false);
        return run;
    }

    public ReconciliationRun approve(String tenantId, String runId, String operator) {
        ReconciliationRun r = get(tenantId, runId);
        if (r.status() != ReconciliationRun.Status.BRAKED) throw new ConflictException("only a braked run can be approved");
        ReconciliationRun approved = r.decidedBy(operator, ReconciliationRun.Status.APPROVED, clock.instant());
        runs.save(approved);
        LOG.warn("reconciliation approved past the safety brake runId={} tenant={} by={}", runId, tenantId, operator);
        enqueue(tenantId, runId, true);
        return approved;
    }

    public ReconciliationRun reject(String tenantId, String runId, String operator) {
        ReconciliationRun r = get(tenantId, runId);
        if (r.status() != ReconciliationRun.Status.BRAKED) throw new ConflictException("only a braked run can be rejected");
        ReconciliationRun rejected = r.decidedBy(operator, ReconciliationRun.Status.REJECTED, clock.instant());
        runs.save(rejected);
        deleteQuietly(exportFile(tenantId, runId));
        return rejected;
    }

    public ReconciliationRun get(String tenantId, String runId) {
        return runs.find(tenantId, runId).orElseThrow(() -> new NotFoundException("run not found"));
    }

    public List<ReconciliationRun> list(String tenantId) {
        return runs.list(tenantId, 50);
    }

    /** After a restart: re-run anything that was in flight (safe, see class comment). */
    public int recoverInterrupted() {
        int n = 0;
        for (ReconciliationRun.Status s : List.of(ReconciliationRun.Status.RECEIVED, ReconciliationRun.Status.RUNNING,
                ReconciliationRun.Status.APPROVED)) {
            for (ReconciliationRun r : runs.findByStatus(s)) {
                if (!Files.exists(exportFile(r.tenantId(), r.runId()))) {
                    runs.save(r.with(ReconciliationRun.Status.FAILED, clock.instant(), "export file lost in the crash; upload again"));
                    continue;
                }
                LOG.info("resuming interrupted reconciliation runId={} tenant={}", r.runId(), r.tenantId());
                enqueue(r.tenantId(), r.runId(), r.decidedBy() != null);
                n++;
            }
        }
        return n;
    }

    /** Wait for this tenant's queued runs (tests and benchmark). */
    public void awaitIdle(String tenantId, long timeoutMs) throws Exception {
        ThreadPoolExecutor w = workers.get(tenantId);
        if (w == null) return;
        Future<?> marker = w.submit(() -> {});
        marker.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int queueDepth() {
        return workers.values().stream().mapToInt(w -> w.getQueue().size() + w.getActiveCount()).sum();
    }

    @Override
    public void close() {
        workers.values().forEach(ThreadPoolExecutor::shutdown);
    }

    // ------------------------------------------------------------------ the run

    private void enqueue(String tenantId, String runId, boolean approved) {
        ThreadPoolExecutor w = workers.computeIfAbsent(tenantId, t -> new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_PER_TENANT), r -> {
            Thread th = new Thread(r, "reconcile-" + t);
            th.setDaemon(true);
            return th;
        }));
        try {
            w.execute(() -> runSafely(tenantId, runId, approved));
        } catch (RejectedExecutionException e) {
            throw new BackpressureException("too many reconciliation runs queued for this tenant");
        }
    }

    private void runSafely(String tenantId, String runId, boolean approved) {
        long t0 = System.nanoTime();
        try {
            run(tenantId, runId, approved);
        } catch (Exception e) {
            LOG.error("reconciliation failed runId={} tenant={} cause={}", runId, tenantId, e.getClass().getSimpleName());
            runs.find(tenantId, runId).ifPresent(r -> runs.save(
                    r.with(ReconciliationRun.Status.FAILED, clock.instant(), "failed: " + e.getClass().getSimpleName() + "; safe to re-run")));
        } finally {
            runs.find(tenantId, runId).ifPresent(r ->
                    metrics.reconciliationRun(tenantId, r.status().name(), (System.nanoTime() - t0) / 1_000_000));
        }
    }

    void run(String tenantId, String runId, boolean approved) throws IOException {
        ReconciliationRun r = get(tenantId, runId).with(ReconciliationRun.Status.RUNNING, clock.instant(), null);
        runs.save(r);
        Path work = dataDir.resolve("work").resolve(runId);
        try {
            ReconciliationEngine.DiffOutput out;
            try (BufferedReader in = Files.newBufferedReader(exportFile(tenantId, runId), StandardCharsets.UTF_8)) {
                out = new ReconciliationEngine(clock).diff(tenantId, in, catalogs.get(tenantId), store, work);
            }
            SafetyBrake.Decision decision = brake.evaluate(out.stats(), out.framing().headerOk());
            r = r.withDiff(out.stats(), decision, out.errorSample().stream().limit(100).toList(), clock.instant());
            runs.save(r);

            if (decision.tripped() && !approved) {
                metrics.brakeTripped(tenantId);
                // ALERT: an operator must look at this run; nothing was applied
                LOG.error("ALERT reconciliation braked runId={} tenant={} appearsMissing={} missingRatio={} reasons={}",
                        runId, tenantId, decision.appearsMissing(), String.format("%.3f", decision.missingRatio()), decision.reasons());
                runs.save(r.with(ReconciliationRun.Status.BRAKED, clock.instant(), decision.message()));
                return;
            }

            Map<String, Long> applied = new TreeMap<>();
            var takenAt = out.stats().takenAt();
            ReconciliationEngine.readActions(tenantId, out.actionsFile(), a -> {
                String key;
                if (a instanceof DiffAction.Upsert u) {
                    key = "upsert." + roster.applyHr(u.event(), ChangeSource.HR_EXPORT).outcome();
                } else {
                    DiffAction.Deactivate d = (DiffAction.Deactivate) a;
                    key = "deactivate." + roster.reconciliationDeactivate(tenantId, d.employeeId(), d.versionSeen(), takenAt).outcome();
                }
                applied.merge(key, 1L, Long::sum);
            });
            r = get(tenantId, runId).withApplied(applied, clock.instant());
            runs.save(r.with(ReconciliationRun.Status.COMPLETED, clock.instant(),
                    approved ? "applied after operator approval" : decision.message()));
            deleteQuietly(exportFile(tenantId, runId));
        } finally {
            deleteTree(work);
        }
    }

    // ------------------------------------------------------------------ files

    private Path exportFile(String tenantId, String runId) {
        if (!tenantId.matches("[a-zA-Z0-9_-]{1,64}") || !runId.matches("[a-f0-9-]{36}"))
            throw new InvalidRequestException("bad id");
        return dataDir.resolve("exports").resolve(tenantId).resolve(runId + ".csv");
    }

    private static long copyLimited(InputStream in, Path file) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FILE_BYTES) {
                    out.close();
                    Files.deleteIfExists(file);
                    throw new InvalidRequestException("export larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB");
                }
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }

    private static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(ReconciliationService::deleteQuietly);
        } catch (IOException ignored) { }
    }
}
