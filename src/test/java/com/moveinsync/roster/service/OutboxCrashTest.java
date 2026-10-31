package com.moveinsync.roster.service;

import com.moveinsync.roster.core.Raw;
import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.outbox.OutboxDraft;
import com.moveinsync.roster.core.outbox.OutboxRelay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failure injection around the outbox: the database write and the event can never disagree. */
class OutboxCrashTest {

    @TempDir
    Path dir;

    private static final String T = Harness.T;

    private void bookTwoTrips(Harness h, String emp) {
        h.bookings.upsertRoute(T, "R1", 4);
        h.bookings.book(T, emp, "R1", Instant.parse("2026-11-02T08:00:00Z"));
        h.bookings.book(T, emp, "R1", Instant.parse("2026-11-03T08:00:00Z"));
    }

    @Test
    void crashBetweenDatabaseWriteAndPublish_eventIsPublishedAfterRestart() {
        Harness first = new Harness(dir);
        first.roster.ingestBatch(T, List.of(Raw.create("E1", 1)));
        first.relay.drainAll(100);
        bookTwoTrips(first, "E1");

        // the deactivation commits (row + outbox event) ... and the process dies before the relay runs
        first.roster.ingestBatch(T, List.of(Raw.deactivate("E1", 2)));
        assertEquals(1, first.store.unpublishedCount());
        assertEquals(2, first.store.route(T, "R1").orElseThrow().seatsTaken(), "side effects not run yet");
        first.close();

        // restart: new application objects over the same database
        Harness restarted = new Harness(first.store, first.runs, first.clock, dir, OutboxRelay.Hooks.NONE);
        assertEquals(1, restarted.relay.drainAll(100));
        assertEquals(0, restarted.store.unpublishedCount());
        assertTrue(restarted.bookings.bookingsOf(T, "E1").stream().allMatch(b -> b.status() == Booking.Status.CANCELLED));
        assertEquals(0, restarted.store.route(T, "R1").orElseThrow().seatsTaken(), "seats freed");
    }

    @Test
    void crashInsideTheTransactionLeavesNeitherRowNorEvent() {
        try (Harness h = new Harness(dir)) {
            h.roster.ingestBatch(T, List.of(Raw.create("E1", 1)));
            Employee before = h.store.find(T, "E1").orElseThrow();
            int events = h.store.allOutboxEvents().size();
            assertThrows(IllegalStateException.class, () -> h.store.inEmployeeTransaction(T, "E1", tx -> {
                Employee cur = tx.current().orElseThrow();
                tx.save(cur.withStatus(com.moveinsync.roster.core.domain.EmployeeStatus.INACTIVE, 9, cur.hrSourceTime(), Instant.now()));
                tx.emit(OutboxDraft.of(T, "E1", com.moveinsync.roster.core.domain.ChangeType.DEACTIVATED,
                        java.util.Set.of(), 9, 0, ChangeSource.HR_API, Instant.now()));
                throw new IllegalStateException("process killed mid-transaction (injected)");
            }));
            assertEquals(before, h.store.find(T, "E1").orElseThrow());
            assertEquals(events, h.store.allOutboxEvents().size());
        }
    }

    @Test
    void crashAfterPublishBeforeMark_redeliveryIsHarmless() {
        Harness first = new Harness(dir);
        first.roster.ingestBatch(T, List.of(Raw.create("E1", 1)));
        first.relay.drainAll(100);
        bookTwoTrips(first, "E1");
        first.roster.ingestBatch(T, List.of(Raw.deactivate("E1", 2)));

        AtomicBoolean crashOnce = new AtomicBoolean(true);
        Harness crashing = new Harness(first.store, first.runs, first.clock, dir, e -> {
            if (crashOnce.getAndSet(false)) throw new OutboxRelay.SimulatedCrash("killed after publish, before mark");
        });
        assertThrows(OutboxRelay.SimulatedCrash.class, () -> crashing.relay.drainAll(100));
        assertEquals(1, crashing.delivered.size(), "delivered once");
        assertEquals(1, crashing.store.unpublishedCount(), "but not marked");

        Harness restarted = new Harness(first.store, first.runs, first.clock, dir, OutboxRelay.Hooks.NONE);
        restarted.relay.drainAll(100);
        assertEquals(1, restarted.delivered.size(), "delivered again (at-least-once)");
        assertEquals(0, restarted.store.unpublishedCount());
        assertEquals(0, restarted.store.route(T, "R1").orElseThrow().seatsTaken(), "seats freed exactly once, not below zero");
        assertEquals(2, restarted.bookings.bookingsOf(T, "E1").stream().filter(b -> b.status() == Booking.Status.CANCELLED).count());
    }

    @Test
    void publishFailureIsRetriedAndPerEmployeeOrderIsKept() {
        try (Harness h = new Harness(dir)) {
            AtomicBoolean fail = new AtomicBoolean(true);
            List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            var relay = new OutboxRelay(h.store, e -> {
                if (fail.get() && e.draft().type() == com.moveinsync.roster.core.domain.ChangeType.UPDATED)
                    throw new java.io.IOException("broker down (injected)");
                order.add(e.employeeId() + ":" + e.draft().type());
            }, h.clock, OutboxRelay.Listener.NONE, OutboxRelay.Hooks.NONE);
            h.roster.ingestBatch(T, List.of(Raw.create("E1", 1), Raw.update("E1", 2, "HYD1", "GEN"), Raw.deactivate("E1", 3),
                    Raw.create("E2", 1)));
            relay.drainOnce(100);
            assertEquals(List.of("E1:CREATED", "E2:CREATED"), order, "E1's later events wait behind the failed one");
            fail.set(false);
            relay.drainAll(100);
            assertEquals(List.of("E1:CREATED", "E2:CREATED", "E1:UPDATED", "E1:DEACTIVATED"), order);
            assertEquals(0, h.store.unpublishedCount());
        }
    }
}
