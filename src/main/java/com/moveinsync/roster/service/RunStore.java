package com.moveinsync.roster.service;

import com.moveinsync.roster.service.model.ReconciliationRun;

import java.util.List;
import java.util.Optional;

/** Reconciliation runs, keyed by tenant. */
public interface RunStore {
    void save(ReconciliationRun run);
    Optional<ReconciliationRun> find(String tenantId, String runId);
    List<ReconciliationRun> list(String tenantId, int limit);
    List<ReconciliationRun> findByStatus(ReconciliationRun.Status status);
}
