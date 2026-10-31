package com.moveinsync.roster.service;

import com.moveinsync.roster.bench.SyntheticRoster;
import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeStatus;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.reconcile.SafetyBrake;
import com.moveinsync.roster.service.model.ReconciliationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationTest {

    @TempDir
    Path dir;

    private static final String T = Harness.T;
    private static final Instant V1_TIME = Instant.parse("2026-10-29T10:00:00Z");
    private static final Instant TAKEN = Instant.parse("2026-10-30T23:00:00Z");

    /** Store holds employees [0, n) at version 1. */
    private Harness withEmployees(int n) {
        Harness h = new Harness(dir.resolve("data"));
        for (int i = 0; i < n; i++) h.roster.applyHr(SyntheticRoster.create(T, i, 1, V1_TIME), ChangeSource.HR_API);
        return h;
    }

    private ReconciliationRun run(Harness h, List<SyntheticRoster.Row> rows, int limit, boolean trailer) throws Exception {
        Path f = dir.resolve("export-" + System.nanoTime() + ".csv");
        SyntheticRoster.writeExport(f, rows, TAKEN, limit, trailer);
        ReconciliationRun r;
        try (InputStream in = Files.newInputStream(f)) {
            r = h.recon.submit(T, in);
        }
        h.recon.awaitIdle(T, 60_000);
        return h.recon.get(T, r.runId());
    }

    private long active(Harness h) { return h.store.countActive(T); }

    @Test
    void identicalExportOpensNoBucketAndChangesNothing() throws Exception {
        try (Harness h = withEmployees(2_000)) {
            ReconciliationRun r = run(h, SyntheticRoster.rows(0, 2_000, 1, V1_TIME, 0), Integer.MAX_VALUE, true);
            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            assertEquals(0, r.stats().bucketsOpened());
            assertEquals(2_000, r.stats().unchanged());
            assertTrue(r.applied().isEmpty());
        }
    }

    @Test
    void appliesNewChangedAndMissingBelowTheThreshold() throws Exception {
        try (Harness h = withEmployees(1_000)) {
            List<SyntheticRoster.Row> rows = new ArrayList<>(SyntheticRoster.rows(0, 950, 1, V1_TIME, 0)); // 950..999 missing (5%)
            for (int i = 0; i < 20; i++) rows.set(i, new SyntheticRoster.Row(SyntheticRoster.id(i), 2, V1_TIME, SyntheticRoster.profile(i, 1)));
            rows.addAll(SyntheticRoster.rows(5_000, 5_010, 1, V1_TIME, 0));                                  // 10 new
            ReconciliationRun r = run(h, rows, Integer.MAX_VALUE, true);
            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            assertEquals(10, r.stats().newEmployees());
            assertEquals(20, r.stats().changed());
            assertEquals(50, r.stats().missing());
            assertEquals(Long.valueOf(30), r.applied().get("upsert.APPLIED"));
            assertEquals(Long.valueOf(50), r.applied().get("deactivate.APPLIED"));
            assertEquals(1_000 - 50 + 10, active(h));
            assertEquals(2, h.store.find(T, SyntheticRoster.id(3)).orElseThrow().hrVersion());
        }
    }

    @Test
    void caseStudyExample_fileCutOffTriggersTheBrakeAndNobodyIsDeactivated() throws Exception {
        try (Harness h = withEmployees(2_000)) {
            // the file should have 2,000 rows but stops at 1,200, with no trailer
            ReconciliationRun r = run(h, SyntheticRoster.rows(0, 2_000, 1, V1_TIME, 0), 1_200, false);
            assertEquals(ReconciliationRun.Status.BRAKED, r.status());
            assertEquals(800, r.brake().appearsMissing());
            assertTrue(r.brake().reasons().contains(SafetyBrake.Reason.TOO_MANY_MISSING));
            assertTrue(r.brake().reasons().contains(SafetyBrake.Reason.TRUNCATED_FILE));
            assertTrue(r.note().contains("800"), r.note());
            assertEquals(2_000, active(h), "nobody deactivated");
            assertEquals(1, h.metrics.count("brake"));

            // a human approves: now it applies
            h.recon.approve(T, r.runId(), "ops-1");
            h.recon.awaitIdle(T, 60_000);
            ReconciliationRun done = h.recon.get(T, r.runId());
            assertEquals(ReconciliationRun.Status.COMPLETED, done.status(), done.note());
            assertEquals("ops-1", done.decidedBy());
            assertEquals(1_200, active(h));
        }
    }

    @Test
    void rejectedRunAppliesNothing() throws Exception {
        try (Harness h = withEmployees(500)) {
            ReconciliationRun r = run(h, SyntheticRoster.rows(0, 500, 1, V1_TIME, 0), 100, false);
            assertEquals(ReconciliationRun.Status.BRAKED, r.status());
            assertEquals(ReconciliationRun.Status.REJECTED, h.recon.reject(T, r.runId(), "ops-1").status());
            assertEquals(500, active(h));
        }
    }

    @Test
    void thresholdIsConfigurable() throws Exception {
        try (Harness h = withEmployees(1_000)) {
            h.recon.setBrake(new SafetyBrake(0.50, true, 20));
            ReconciliationRun r = run(h, SyntheticRoster.rows(0, 600, 1, V1_TIME, 0), Integer.MAX_VALUE, true); // 40% missing
            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            assertEquals(600, active(h));
        }
    }

    @Test
    void invalidRowsAreReportedAndTheirEmployeesAreNotDeactivated() throws Exception {
        try (Harness h = withEmployees(300)) {
            List<SyntheticRoster.Row> rows = new ArrayList<>(SyntheticRoster.rows(0, 300, 1, V1_TIME, 0));
            var p = rows.get(7).p();
            rows.set(7, new SyntheticRoster.Row(rows.get(7).id(), 2, V1_TIME,
                    new com.moveinsync.roster.core.domain.HrProfile(p.name(), p.email(), "12345", null, "MARS1", p.shiftCode())));
            ReconciliationRun r = run(h, rows, Integer.MAX_VALUE, true);
            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            assertEquals(1, r.stats().invalidRows());
            assertEquals(0, r.stats().missing(), "an employee with a bad row is still present");
            var err = r.errorSample().getFirst();
            assertEquals(SyntheticRoster.id(7), err.employeeId());
            assertEquals(10, err.lineNo());
            assertTrue(err.errors().toString().contains("UNKNOWN_OFFICE"));
            assertTrue(!err.toString().contains("12345") && !err.toString().contains("@"), "no values, no personal data");
            assertEquals(EmployeeStatus.ACTIVE, h.store.find(T, SyntheticRoster.id(7)).orElseThrow().status());
        }
    }

    @Test
    void exportOlderThanTheLiveFeedNeverRollsBack() throws Exception {
        try (Harness h = withEmployees(200)) {
            // the live feed moved E5 to v3 and hired E900 after the export was taken
            h.roster.applyHr(new HrEvent(T, HrEvent.Type.UPDATE, SyntheticRoster.id(5), 3, TAKEN.plusSeconds(60),
                    SyntheticRoster.profile(5, 9), java.util.Set.of()), ChangeSource.HR_API);
            h.roster.applyHr(SyntheticRoster.create(T, 900, 1, TAKEN.plusSeconds(120)), ChangeSource.HR_API);
            ReconciliationRun r = run(h, SyntheticRoster.rows(0, 200, 1, V1_TIME, 0), Integer.MAX_VALUE, true);
            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            Employee e5 = h.store.find(T, SyntheticRoster.id(5)).orElseThrow();
            assertEquals(3, e5.hrVersion(), "stale export row ignored");
            assertEquals(Long.valueOf(1), r.applied().get("upsert.STALE"));
            assertEquals(EmployeeStatus.ACTIVE, h.store.find(T, SyntheticRoster.id(900)).orElseThrow().status(),
                    "hired after the export: not deactivated");
            assertEquals(Long.valueOf(1), r.applied().get("deactivate.STALE"));
        }
    }

    @Test
    void replayingTheSameFileTwiceChangesNothingTheSecondTime() throws Exception {
        try (Harness h = withEmployees(1_000)) {
            List<SyntheticRoster.Row> rows = new ArrayList<>(SyntheticRoster.rows(0, 980, 2, V1_TIME, 1));
            run(h, rows, Integer.MAX_VALUE, true);
            var snapshot = h.store.list(T, null, 5_000);
            int events = h.store.allOutboxEvents().size();
            ReconciliationRun second = run(h, rows, Integer.MAX_VALUE, true);
            assertEquals(ReconciliationRun.Status.COMPLETED, second.status());
            assertTrue(second.applied().isEmpty(), second.applied().toString());
            assertEquals(snapshot, h.store.list(T, null, 5_000));
            assertEquals(events, h.store.allOutboxEvents().size());
        }
    }

    @Test
    void crashMidRunIsRecoveredByRunningAgain() throws Exception {
        Harness h = withEmployees(1_000);
        List<SyntheticRoster.Row> rows = SyntheticRoster.rows(0, 1_000, 2, V1_TIME, 1);   // every row changes
        // a roster service that "dies" after 400 writes
        RosterService dying = new RosterService(h.store, h.catalogs, h.metrics, h.clock) {
            int n = 0;
            @Override public ApplyResult applyHr(HrEvent ev, ChangeSource s) {
                if (++n > 400) throw new IllegalStateException("process killed mid-run (injected)");
                return super.applyHr(ev, s);
            }
        };
        ReconciliationService crashing = new ReconciliationService(h.store, dying, h.catalogs, h.runs, h.metrics, h.clock,
                dir.resolve("data"), SafetyBrake.defaults());
        Path f = dir.resolve("export.csv");
        SyntheticRoster.writeExport(f, rows, TAKEN, Integer.MAX_VALUE, true);
        ReconciliationRun r;
        try (InputStream in = Files.newInputStream(f)) { r = crashing.submit(T, in); }
        crashing.awaitIdle(T, 60_000);
        assertEquals(ReconciliationRun.Status.FAILED, crashing.get(T, r.runId()).status());
        crashing.close();

        // operator (or the nightly job) re-runs the same file
        ReconciliationRun again = run(h, rows, Integer.MAX_VALUE, true);
        assertEquals(ReconciliationRun.Status.COMPLETED, again.status());
        assertEquals(Long.valueOf(600), again.applied().get("upsert.APPLIED"), "only the part that had not been applied");
        for (int i = 0; i < 1_000; i++) assertEquals(2, h.store.find(T, SyntheticRoster.id(i)).orElseThrow().hrVersion());
        h.close();
    }
}
