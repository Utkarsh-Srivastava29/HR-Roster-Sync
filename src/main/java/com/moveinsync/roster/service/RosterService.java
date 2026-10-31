package com.moveinsync.roster.service;

import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.TransportEdit;
import com.moveinsync.roster.core.ingest.ChangePolicy;
import com.moveinsync.roster.core.ingest.Decision;
import com.moveinsync.roster.core.ingest.Outcome;
import com.moveinsync.roster.core.outbox.OutboxDraft;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.core.validation.FieldError;
import com.moveinsync.roster.core.validation.RawHrRecord;
import com.moveinsync.roster.core.validation.RecordValidator;
import com.moveinsync.roster.core.validation.TenantCatalog;
import com.moveinsync.roster.core.validation.ValidationResult;
import com.moveinsync.roster.service.cache.TtlLruCache;
import com.moveinsync.roster.service.error.ConflictException;
import com.moveinsync.roster.service.error.InvalidRequestException;
import com.moveinsync.roster.service.error.NotFoundException;
import com.moveinsync.roster.service.error.ServiceUnavailableException;
import com.moveinsync.roster.service.model.BatchReport;
import com.moveinsync.roster.service.model.RecordResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Applies changes to employee records. Each record is its own transaction: validation happens
 * first (outside any lock), then the {@link ChangePolicy} decides inside a per-employee
 * transaction, and the new row and its change event are committed together.
 */
public class RosterService {

    private static final Logger LOG = LoggerFactory.getLogger(RosterService.class);

    public static final int MAX_BATCH = 5_000;
    /** Single-employee reads may be this stale on another instance (see README, Caching). */
    public static final long EMPLOYEE_CACHE_TTL_MS = 10_000;

    private final RosterStore store;
    private final CatalogService catalogs;
    private final RosterMetrics metrics;
    private final Clock clock;
    private final ChangePolicy policy = new ChangePolicy();
    private final RecordValidator validator = new RecordValidator();
    private final TtlLruCache<String, Optional<Employee>> employeeCache = new TtlLruCache<>(100_000, EMPLOYEE_CACHE_TTL_MS);

    public RosterService(RosterStore store, CatalogService catalogs, RosterMetrics metrics, Clock clock) {
        this.store = store;
        this.catalogs = catalogs;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What happened to one change. */
    public record ApplyResult(Outcome outcome, List<String> changedFields, String reason) {}

    // ------------------------------------------------------------------ HR feed

    public BatchReport ingestBatch(String tenantId, List<RawHrRecord> records) {
        if (records == null || records.isEmpty()) throw new InvalidRequestException("batch is empty");
        if (records.size() > MAX_BATCH) throw new InvalidRequestException("batch larger than " + MAX_BATCH + " records");
        TenantCatalog catalog = catalogs.get(tenantId);
        Instant now = clock.instant();
        List<RecordResult> results = new ArrayList<>(records.size());
        Map<String, Integer> totals = new TreeMap<>();
        for (int i = 0; i < records.size(); i++) {
            RecordResult r = ingestOne(tenantId, i, records.get(i), catalog, now);
            results.add(r);
            totals.merge(r.outcome().name(), 1, Integer::sum);
        }
        return new BatchReport(records.size(), totals, results);
    }

    private RecordResult ingestOne(String tenantId, int index, RawHrRecord raw, TenantCatalog catalog, Instant now) {
        long t0 = System.nanoTime();
        ValidationResult vr = validator.validate(tenantId, raw, catalog, now);
        String id = raw == null ? null : RecordValidator.normalise("employeeId", raw.employeeId());
        String safeId = id != null && id.matches(RecordValidator.ID_REGEX) ? id : null;
        if (!vr.valid()) {
            metrics.record(tenantId, ChangeSource.HR_API.name(), Outcome.REJECTED.name());
            for (FieldError fe : vr.errors()) metrics.rejectedField(tenantId, fe.field(), fe.code().name());
            return new RecordResult(index, safeId, Outcome.REJECTED, vr.errors(), List.of(), List.of(), "validation failed");
        }
        HrEvent ev = vr.event();
        try {
            ApplyResult ar = applyHr(ev, ChangeSource.HR_API);
            metrics.ingestLatency(tenantId, (System.nanoTime() - t0) / 1_000);
            List<FieldError> errs = ar.outcome() == Outcome.CONFLICT
                    ? List.of(new FieldError("version", com.moveinsync.roster.core.validation.ErrorCode.VERSION_REUSED)) : List.of();
            return new RecordResult(index, ev.employeeId(), ar.outcome(), errs, ar.changedFields(),
                    ev.ignoredFields().stream().map(Enum::name).sorted().toList(), ar.reason());
        } catch (RuntimeException e) {
            // a storage failure for one record must not fail the others; the sender can retry it
            LOG.warn("record failed employeeId={} cause={}", ev.employeeId(), e.getClass().getSimpleName());
            throw e instanceof ServiceUnavailableException s ? s
                    : new ServiceUnavailableException("storage unavailable; retry the batch (it is idempotent)", e);
        }
    }

    /** Apply one validated HR change (API feed or nightly export). */
    public ApplyResult applyHr(HrEvent ev, ChangeSource source) {
        Instant now = clock.instant();
        Decision d = store.inEmployeeTransaction(ev.tenantId(), ev.employeeId(), tx -> {
            Decision dec = policy.decideHr(tx.current(), ev, now);
            commit(tx, dec, source, now);
            return dec;
        });
        afterWrite(ev.tenantId(), ev.employeeId(), source, d);
        return new ApplyResult(d.outcome(), names(d), d.reason());
    }

    /** Reconciliation decided this employee is missing from the export. */
    public ApplyResult reconciliationDeactivate(String tenantId, String employeeId, long versionSeen, Instant exportTakenAt) {
        Instant now = clock.instant();
        Decision d = store.inEmployeeTransaction(tenantId, employeeId, tx -> {
            Decision dec = policy.decideReconciliationDeactivate(tx.current(), versionSeen, exportTakenAt, now);
            commit(tx, dec, ChangeSource.RECONCILIATION, now);
            return dec;
        });
        afterWrite(tenantId, employeeId, ChangeSource.RECONCILIATION, d);
        return new ApplyResult(d.outcome(), names(d), d.reason());
    }

    // ------------------------------------------------------------------ transport admin

    public Employee editTransport(String tenantId, String employeeId, TransportEdit edit) {
        if (edit.pickupPoint() != null && edit.pickupPoint().length() > 200) throw new InvalidRequestException("pickupPoint too long");
        if (edit.specialAssistance() != null && edit.specialAssistance().size() > 10)
            throw new InvalidRequestException("at most 10 special-assistance needs");
        Instant now = clock.instant();
        Decision d = store.inEmployeeTransaction(tenantId, employeeId, tx -> {
            Employee cur = tx.current().filter(e -> !e.hr().unknown())
                    .orElseThrow(() -> new NotFoundException("employee not found"));
            Decision dec = policy.decideTransport(cur, edit, now);
            commit(tx, dec, ChangeSource.TRANSPORT_ADMIN, now);
            return dec;
        });
        afterWrite(tenantId, employeeId, ChangeSource.TRANSPORT_ADMIN, d);
        if (d.outcome() == Outcome.CONFLICT) throw new ConflictException(d.reason());
        return store.find(tenantId, employeeId).orElseThrow();
    }

    // ------------------------------------------------------------------ reads

    public Employee get(String tenantId, String employeeId) {
        return employeeCache.getOrCompute(tenantId + "|" + employeeId, k -> store.find(tenantId, employeeId))
                .filter(e -> !e.hr().unknown())
                .orElseThrow(() -> new NotFoundException("employee not found"));
    }

    public List<Employee> list(String tenantId, String after, int limit) {
        return store.list(tenantId, after, Math.max(1, Math.min(limit, 500))).stream().filter(e -> !e.hr().unknown()).toList();
    }

    public TtlLruCache<String, Optional<Employee>> employeeCache() { return employeeCache; }

    // ------------------------------------------------------------------ internals

    private static void commit(RosterStore.EmployeeTx tx, Decision d, ChangeSource source, Instant now) {
        if (!d.writes()) return;
        tx.save(d.next());
        if (d.emitsEvent()) {
            Employee e = d.next();
            tx.emit(OutboxDraft.of(e.tenantId(), e.employeeId(), d.changeType(), d.changed(), e.hrVersion(),
                    e.transportVersion(), source, now));
        }
    }

    private void afterWrite(String tenantId, String employeeId, ChangeSource source, Decision d) {
        if (d.writes()) employeeCache.invalidateIf(k -> k.equals(tenantId + "|" + employeeId));
        metrics.record(tenantId, source.name(), d.outcome().name());
        if (d.outcome() == Outcome.CONFLICT || d.outcome() == Outcome.STALE)
            LOG.info("change not applied employeeId={} source={} outcome={}", employeeId, source, d.outcome());
    }

    private static List<String> names(Decision d) {
        return d.changed().stream().map(EmployeeField::name).sorted().toList();
    }
}
