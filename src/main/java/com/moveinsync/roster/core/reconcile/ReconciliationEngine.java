package com.moveinsync.roster.core.reconcile;

import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.store.Buckets;
import com.moveinsync.roster.core.store.DigestRow;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.core.validation.FieldError;
import com.moveinsync.roster.core.validation.RecordValidator;
import com.moveinsync.roster.core.validation.TenantCatalog;
import com.moveinsync.roster.core.validation.ValidationResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded-memory diff of a nightly export against the store.
 *
 * <ol>
 *   <li><b>Pass 1 - stream the file once.</b> Validate each row; add its (id, content hash) to its
 *       bucket's XOR digest; append the row to one of {@link #PARTITIONS} spill files on disk
 *       (partition = bucket mod 64). Memory: the 4,096 bucket digests.</li>
 *   <li><b>Compare digests</b> with the store's per-bucket digests. Equal bucket = identical
 *       content, skipped without reading a single row.</li>
 *   <li><b>Pass 2 - per partition.</b> Load only the file rows and store rows of the differing
 *       buckets in that partition, classify them (new / changed / missing / unchanged) and append
 *       actions to an action file. Memory: one partition's differing buckets (~1/64 of the rows
 *       in the worst case).</li>
 * </ol>
 * Time O(N) for the file + O(rows in differing buckets) for the store; memory O(B + N/64).
 */
public final class ReconciliationEngine {

    public static final int PARTITIONS = 64;
    public static final int MAX_ERRORS_KEPT = 1_000;

    private final RecordValidator validator = new RecordValidator();
    private final Clock clock;

    public ReconciliationEngine(Clock clock) {
        this.clock = clock;
    }

    /** Result of a diff: counts, framing, the action file (delete when done) and an error sample. */
    public record DiffOutput(DiffStats stats, ExportReader.Framing framing, Path actionsFile, long actionCount,
                             List<RowError> errorSample) {}

    public DiffOutput diff(String tenantId, BufferedReader file, TenantCatalog catalog, RosterStore store, Path workDir)
            throws IOException {
        long t0 = System.nanoTime();
        Files.createDirectories(workDir);
        long[] fileDigest = new long[Buckets.COUNT];
        List<RowError> errors = new ArrayList<>();
        Map<String, Long> errorsByCode = new TreeMap<>();
        long[] valid = {0}, invalid = {0};
        Instant[] takenAt = {null};

        // ---------------- pass 1
        BufferedWriter[] spill = new BufferedWriter[PARTITIONS];
        Path[] spillPath = new Path[PARTITIONS];
        for (int p = 0; p < PARTITIONS; p++) {
            spillPath[p] = workDir.resolve("part-" + p + ".tsv");
            spill[p] = Files.newBufferedWriter(spillPath[p], StandardCharsets.UTF_8);
        }
        ExportReader.Framing framing;
        try {
            Instant fallbackTime = clock.instant();
            framing = new ExportReader().read(file, (lineNo, raw) -> {
                ValidationResult vr = validator.validate(tenantId, raw, catalog, fallbackTime);
                String id = RecordValidator.normalise("employeeId", raw.employeeId());
                boolean idOk = id != null && id.matches(RecordValidator.ID_REGEX);
                try {
                    if (vr.valid()) {
                        valid[0]++;
                        HrEvent e = vr.event();
                        int b = Buckets.of(e.employeeId());
                        long h = e.profile().contentHash();
                        fileDigest[b] ^= Buckets.digest(e.employeeId(), h);
                        spill[b % PARTITIONS].write(b + "\tV\t" + h + "\t" + DiffAction.encode(new DiffAction.Upsert(e)) + "\n");
                    } else {
                        invalid[0]++;
                        for (FieldError fe : vr.errors()) errorsByCode.merge(fe.field() + ":" + fe.code(), 1L, Long::sum);
                        if (errors.size() < MAX_ERRORS_KEPT) errors.add(new RowError(lineNo, idOk ? id : null, vr.errors()));
                        // a present-but-invalid row still proves the employee exists: never deactivate them for it
                        if (idOk) { int b = Buckets.of(id); spill[b % PARTITIONS].write(b + "\tI\t" + id + "\n"); }
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        } finally {
            for (BufferedWriter w : spill) w.close();
        }
        takenAt[0] = framing.takenAt() == null ? clock.instant() : framing.takenAt();

        // ---------------- compare digests
        long[] storeDigest = store.bucketDigests(tenantId);
        BitSet differing = new BitSet(Buckets.COUNT);
        for (int b = 0; b < Buckets.COUNT; b++) if (storeDigest[b] != fileDigest[b]) differing.set(b);
        // buckets holding invalid rows must be opened too (their employees are present but not in the digest)
        for (RowError re : errors) if (re.employeeId() != null) differing.set(Buckets.of(re.employeeId()));
        long activeInStore = store.countActive(tenantId);

        // ---------------- pass 2
        Path actions = workDir.resolve("actions.tsv");
        long newCount = 0, changed = 0, missing = 0, dupRows = 0, actionCount = 0, maxInMemory = 0;
        try (BufferedWriter out = Files.newBufferedWriter(actions, StandardCharsets.UTF_8)) {
            for (int p = 0; p < PARTITIONS; p++) {
                BitSet here = new BitSet(Buckets.COUNT);
                for (int b = p; b < Buckets.COUNT; b += PARTITIONS) if (differing.get(b)) here.set(b);
                if (here.isEmpty()) { Files.deleteIfExists(spillPath[p]); continue; }

                Map<String, String[]> fileRows = new HashMap<>();     // id -> {hash, actionLine, version}
                Set<String> presentInvalid = new HashSet<>();
                try (BufferedReader r = Files.newBufferedReader(spillPath[p], StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String[] f = line.split("\t", 4);
                        if (!here.get(Integer.parseInt(f[0]))) continue;
                        if (f[1].equals("I")) { presentInvalid.add(f[2]); continue; }
                        String actionLine = f[3];
                        String[] a = actionLine.split("\t", 4);
                        String id = a[1];
                        long version = Long.parseLong(a[2]);
                        String[] prev = fileRows.get(id);
                        if (prev != null) {
                            dupRows++;
                            if (Long.parseLong(prev[2]) >= version) continue;
                        }
                        fileRows.put(id, new String[]{f[2], actionLine, Long.toString(version)});
                    }
                }
                Files.deleteIfExists(spillPath[p]);

                Map<String, DigestRow> storeRows = new HashMap<>();
                store.scanBuckets(tenantId, here, row -> storeRows.put(row.employeeId(), row));
                maxInMemory = Math.max(maxInMemory, fileRows.size() + storeRows.size() + presentInvalid.size());

                for (Map.Entry<String, String[]> e : fileRows.entrySet()) {
                    DigestRow s = storeRows.get(e.getKey());
                    long h = Long.parseLong(e.getValue()[0]);
                    if (s == null) newCount++;
                    else if (s.active() && s.hrHash() == h) continue;
                    else changed++;
                    out.write(e.getValue()[1]);
                    out.write('\n');
                    actionCount++;
                }
                for (DigestRow s : storeRows.values()) {
                    if (!s.active() || fileRows.containsKey(s.employeeId()) || presentInvalid.contains(s.employeeId())) continue;
                    missing++;
                    out.write(DiffAction.encode(new DiffAction.Deactivate(s.employeeId(), s.hrVersion())));
                    out.write('\n');
                    actionCount++;
                }
            }
        }
        long unchanged = valid[0] - dupRows - newCount - changed;
        DiffStats stats = new DiffStats(framing.rowsRead(), valid[0], invalid[0], errorsByCode, newCount, changed,
                Math.max(0, unchanged), missing, activeInStore, dupRows, Buckets.COUNT, differing.cardinality(),
                maxInMemory, framing.trailerRows(), takenAt[0], (System.nanoTime() - t0) / 1_000_000);
        return new DiffOutput(stats, framing, actions, actionCount, errors);
    }

    /** Stream the action file back. */
    public static void readActions(String tenantId, Path actions, java.util.function.Consumer<DiffAction> sink) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(actions, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) sink.accept(DiffAction.decode(tenantId, line));
        }
    }
}
