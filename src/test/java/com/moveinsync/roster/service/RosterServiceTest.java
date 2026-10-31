package com.moveinsync.roster.service;

import com.moveinsync.roster.core.Raw;
import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.TransportEdit;
import com.moveinsync.roster.core.ingest.Outcome;
import com.moveinsync.roster.core.validation.RawHrRecord;
import com.moveinsync.roster.service.error.ConflictException;
import com.moveinsync.roster.service.model.BatchReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterServiceTest {

    @TempDir
    Path dir;

    private static final String T = Harness.T;

    @Test
    void caseStudyExample_transferV7BeforeShiftChangeV6() {
        try (Harness h = new Harness(dir)) {
            h.roster.ingestBatch(T, List.of(Raw.update("E100", 5, "BLR1", "GEN")));
            // v7: office transfer (HR sends the full profile: new office, and the shift as of v7)
            BatchReport r7 = h.roster.ingestBatch(T, List.of(Raw.update("E100", 7, "HYD1", "NIGHT")));
            // v6: the older shift change arrives late
            BatchReport r6 = h.roster.ingestBatch(T, List.of(Raw.update("E100", 6, "BLR1", "EVE")));
            assertEquals(Outcome.APPLIED, r7.results().getFirst().outcome());
            assertEquals(Outcome.STALE, r6.results().getFirst().outcome());
            Employee e = h.roster.get(T, "E100");
            assertEquals("HYD1", e.hr().officeCode());
            assertEquals("NIGHT", e.hr().shiftCode());
            assertEquals(7, e.hrVersion());
        }
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        try (Harness h = new Harness(dir)) {
            List<RawHrRecord> feed = new ArrayList<>();
            for (int i = 0; i < 50; i++) feed.add(Raw.create("E" + i, 1));
            for (int i = 0; i < 50; i += 2) feed.add(Raw.update("E" + i, 2, "BLR2", "EVE"));
            for (int i = 0; i < 50; i += 5) feed.add(Raw.deactivate("E" + i, 3));
            h.roster.ingestBatch(T, feed);
            var before = h.store.list(T, null, 1000);
            int events = h.store.allOutboxEvents().size();

            BatchReport again = h.roster.ingestBatch(T, feed);
            assertEquals(h.store.list(T, null, 1000), before, "records identical");
            assertEquals(events, h.store.allOutboxEvents().size(), "no new change events");
            assertTrue(again.results().stream().allMatch(r -> r.outcome() == Outcome.DUPLICATE || r.outcome() == Outcome.STALE),
                    again.totals().toString());
        }
    }

    @Test
    void oneBadRecordNeverFailsTheBatch() {
        try (Harness h = new Harness(dir)) {
            BatchReport r = h.roster.ingestBatch(T, List.of(Raw.create("E1", 1),
                    Raw.withPhone(Raw.create("E2", 1), "not-a-phone"),
                    Raw.update("E3", 1, "NOWHERE", "GEN"),
                    Raw.create("E4", 1)));
            assertEquals(Integer.valueOf(2), r.totals().get("APPLIED"));
            assertEquals(Integer.valueOf(2), r.totals().get("REJECTED"));
            assertEquals("phone", r.results().get(1).errors().getFirst().field());
            assertEquals("officeCode", r.results().get(2).errors().getFirst().field());
            assertFalse(r.toString().contains("not-a-phone"), "report never echoes values");
            assertFalse(r.toString().contains("acme.example"), "report never contains e-mails");
        }
    }

    @Test
    void hrNeverOverwritesTransportOwnedFields() {
        try (Harness h = new Harness(dir)) {
            h.roster.ingestBatch(T, List.of(Raw.create("E1", 1)));
            h.roster.editTransport(T, "E1", new TransportEdit("Gate 4", List.of("WHEELCHAIR"), 0));
            BatchReport r = h.roster.ingestBatch(T, List.of(Raw.withPickup(Raw.update("E1", 2, "BLR2", "GEN"), "Somewhere else")));
            assertEquals(List.of("PICKUP_POINT"), r.results().getFirst().ignoredFields());
            Employee e = h.roster.get(T, "E1");
            assertEquals("Gate 4", e.transport().pickupPoint());
            assertEquals("BLR2", e.hr().officeCode());
            assertThrows(ConflictException.class, () -> h.roster.editTransport(T, "E1", new TransportEdit("Gate 9", List.of(), 0)));
        }
    }

    @Test
    void everyAppliedChangeHasExactlyOneEventAndNothingElseDoes() {
        try (Harness h = new Harness(dir)) {
            BatchReport r = h.roster.ingestBatch(T, List.of(Raw.create("E1", 1), Raw.create("E1", 1), Raw.update("E1", 1, "HYD1", "GEN"),
                    Raw.update("E1", 2, "HYD1", "GEN"), Raw.update("E1", 3, "HYD1", "GEN"), Raw.deactivate("E1", 4)));
            assertEquals(List.of(Outcome.APPLIED, Outcome.DUPLICATE, Outcome.CONFLICT, Outcome.APPLIED, Outcome.UNCHANGED, Outcome.APPLIED),
                    r.results().stream().map(x -> x.outcome()).toList());
            var types = h.store.allOutboxEvents().stream().map(e -> e.draft().type()).toList();
            assertEquals(List.of(ChangeType.CREATED, ChangeType.UPDATED, ChangeType.DEACTIVATED), types);
            assertEquals("version", r.results().get(2).errors().getFirst().field());
        }
    }

    @Test
    void tenantsAreIsolated() {
        try (Harness h = new Harness(dir)) {
            h.roster.ingestBatch(T, List.of(Raw.create("E1", 1)));
            assertThrows(com.moveinsync.roster.service.error.NotFoundException.class, () -> h.roster.get("globex", "E1"));
            assertTrue(h.store.changesAfter("globex", 0, 100).isEmpty());
            assertEquals(1, h.store.changesAfter(T, 0, 100).size());
        }
    }
}
