package com.moveinsync.roster.service.model;

import com.moveinsync.roster.core.reconcile.DiffStats;
import com.moveinsync.roster.core.reconcile.RowError;
import com.moveinsync.roster.core.reconcile.SafetyBrake;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One nightly reconciliation.
 * <pre>
 * RECEIVED -> RUNNING -> COMPLETED
 *                     -> BRAKED -> (operator) APPROVED -> RUNNING -> COMPLETED
 *                               -> (operator) REJECTED
 *                     -> FAILED (error; re-runnable, every change is version-checked)
 * </pre>
 */
public record ReconciliationRun(String runId,
                                String tenantId,
                                Status status,
                                Instant createdAt,
                                Instant updatedAt,
                                long fileBytes,
                                DiffStats stats,
                                SafetyBrake.Decision brake,
                                Map<String, Long> applied,
                                List<RowError> errorSample,
                                String decidedBy,
                                String note) {

    public enum Status { RECEIVED, RUNNING, BRAKED, APPROVED, COMPLETED, REJECTED, FAILED }

    public ReconciliationRun {
        applied = applied == null ? Map.of() : Map.copyOf(applied);
        errorSample = errorSample == null ? List.of() : List.copyOf(errorSample);
    }

    public ReconciliationRun with(Status s, Instant now, String note) {
        return new ReconciliationRun(runId, tenantId, s, createdAt, now, fileBytes, stats, brake, applied, errorSample, decidedBy, note);
    }

    public ReconciliationRun withDiff(DiffStats st, SafetyBrake.Decision b, List<RowError> errs, Instant now) {
        return new ReconciliationRun(runId, tenantId, status, createdAt, now, fileBytes, st, b, applied, errs, decidedBy, note);
    }

    public ReconciliationRun withApplied(Map<String, Long> a, Instant now) {
        return new ReconciliationRun(runId, tenantId, status, createdAt, now, fileBytes, stats, brake, a, errorSample, decidedBy, note);
    }

    public ReconciliationRun decidedBy(String who, Status s, Instant now) {
        return new ReconciliationRun(runId, tenantId, s, createdAt, now, fileBytes, stats, brake, applied, errorSample, who, note);
    }
}
