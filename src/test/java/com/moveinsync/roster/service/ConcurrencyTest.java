package com.moveinsync.roster.service;

import com.moveinsync.roster.bench.SyntheticRoster;
import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.ingest.Outcome;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 16 threads deliver every version of 200 employees, shuffled and each sent twice. Whatever the
 * interleaving: every employee ends on its highest version's content, each version is applied at
 * most once, every applied change has exactly one event, and each employee's events are in
 * increasing version order.
 */
class ConcurrencyTest {

    @TempDir
    Path dir;

    @Test
    void shuffledDuplicatedConcurrentDeliveryConvergesToTheHighestVersion() throws Exception {
        try (Harness h = new Harness(dir)) {
            int employees = 200, versions = 20;
            List<HrEvent> all = new ArrayList<>();
            Instant t = Instant.parse("2026-10-30T10:00:00Z");
            for (int i = 0; i < employees; i++)
                for (int v = 1; v <= versions; v++)
                    for (int copy = 0; copy < 2; copy++)
                        all.add(new HrEvent(Harness.T, HrEvent.Type.UPDATE, SyntheticRoster.id(i), v, t.plusSeconds(v),
                                SyntheticRoster.profile(i, v), Set.of()));
            Collections.shuffle(all, new Random(7));

            ExecutorService pool = Executors.newFixedThreadPool(16);
            AtomicInteger applied = new AtomicInteger();
            List<Future<?>> fs = new ArrayList<>();
            for (HrEvent e : all) fs.add(pool.submit(() -> {
                var r = h.roster.applyHr(e, ChangeSource.HR_API);
                if (r.outcome() == Outcome.APPLIED) applied.incrementAndGet();
            }));
            for (Future<?> f : fs) f.get();
            pool.shutdown();

            for (int i = 0; i < employees; i++) {
                var e = h.store.find(Harness.T, SyntheticRoster.id(i)).orElseThrow();
                assertEquals(versions, e.hrVersion());
                assertEquals(SyntheticRoster.profile(i, versions).contentHash(), e.hrHash());
            }
            List<OutboxEvent> events = h.store.allOutboxEvents();
            assertEquals(applied.get(), events.size(), "one event per applied change, none lost, none doubled");
            Map<String, Long> lastVersion = new HashMap<>();
            for (OutboxEvent e : events) {                        // seq order
                long prev = lastVersion.getOrDefault(e.employeeId(), 0L);
                assertTrue(e.draft().hrVersion() > prev, "per-employee order broken for " + e.employeeId());
                lastVersion.put(e.employeeId(), e.draft().hrVersion());
            }
        }
    }
}
