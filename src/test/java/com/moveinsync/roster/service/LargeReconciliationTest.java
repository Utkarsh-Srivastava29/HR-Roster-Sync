package com.moveinsync.roster.service;

import com.moveinsync.roster.bench.SyntheticRoster;
import com.moveinsync.roster.core.domain.ChangeSource;
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

/** The case-study proof: diff a 200,000-row snapshot in under 60 s with bounded memory. */
class LargeReconciliationTest {

    @TempDir
    Path dir;

    @Test
    void diffs200kRowsUnder60SecondsWithBoundedMemory() throws Exception {
        int n = 200_000;
        Instant v1 = Instant.parse("2026-10-29T10:00:00Z");
        try (Harness h = new Harness(dir.resolve("data"))) {
            for (int i = 0; i < n; i++) h.roster.applyHr(SyntheticRoster.create(Harness.T, i, 1, v1), ChangeSource.HR_API);

            List<SyntheticRoster.Row> rows = new ArrayList<>(SyntheticRoster.rows(0, n - 1_000, 1, v1, 0));
            for (int i = 0; i < 1_000; i++) rows.set(i * 150, new SyntheticRoster.Row(SyntheticRoster.id(i * 150), 2, v1,
                    SyntheticRoster.profile(i * 150, 1)));
            Path f = dir.resolve("export.csv");
            SyntheticRoster.writeExport(f, rows, Instant.parse("2026-10-30T23:00:00Z"), Integer.MAX_VALUE, true);

            long t0 = System.nanoTime();
            ReconciliationRun r;
            try (InputStream in = Files.newInputStream(f)) { r = h.recon.submit(Harness.T, in); }
            h.recon.awaitIdle(Harness.T, 120_000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            r = h.recon.get(Harness.T, r.runId());

            assertEquals(ReconciliationRun.Status.COMPLETED, r.status(), r.note());
            assertTrue(ms < 60_000, "took " + ms + " ms");
            assertEquals(1_000, r.stats().changed());
            assertEquals(1_000, r.stats().missing());
            assertTrue(r.stats().maxRowsInMemory() < n / 20, "held " + r.stats().maxRowsInMemory() + " rows at once");
            assertEquals(n - 1_000, h.store.countActive(Harness.T));
        }
    }
}
