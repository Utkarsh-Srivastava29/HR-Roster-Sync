package com.moveinsync.roster.persistence.memory;

import com.moveinsync.roster.service.RunStore;
import com.moveinsync.roster.service.model.ReconciliationRun;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRunStore implements RunStore {

    private final Map<String, ReconciliationRun> runs = new ConcurrentHashMap<>();

    @Override public void save(ReconciliationRun r) { runs.put(r.tenantId() + "|" + r.runId(), r); }

    @Override public Optional<ReconciliationRun> find(String tenantId, String runId) {
        return Optional.ofNullable(runs.get(tenantId + "|" + runId));
    }

    @Override public List<ReconciliationRun> list(String tenantId, int limit) {
        return runs.values().stream().filter(r -> r.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(ReconciliationRun::createdAt).reversed()).limit(limit).toList();
    }

    @Override public List<ReconciliationRun> findByStatus(ReconciliationRun.Status s) {
        return runs.values().stream().filter(r -> r.status() == s).toList();
    }
}
