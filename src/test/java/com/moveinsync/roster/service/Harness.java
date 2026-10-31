package com.moveinsync.roster.service;

import com.moveinsync.roster.bench.SyntheticRoster;
import com.moveinsync.roster.core.booking.DeactivationSideEffects;
import com.moveinsync.roster.core.outbox.EventPublisher;
import com.moveinsync.roster.core.outbox.InProcessEventBus;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import com.moveinsync.roster.core.outbox.OutboxRelay;
import com.moveinsync.roster.core.reconcile.SafetyBrake;
import com.moveinsync.roster.persistence.memory.InMemoryRunStore;
import com.moveinsync.roster.persistence.memory.InMemoryStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires the application without Spring. The store plays the database: building a second
 * Harness over the same store models "the application process restarted, the database did not".
 */
final class Harness implements AutoCloseable {

    static final String T = "acme";

    /** A clock the test can move. */
    static final class MutableClock extends Clock {
        volatile Instant now;
        MutableClock(Instant start) { now = start; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    final InMemoryStore store;
    final MutableClock clock;
    final InMemoryRosterMetrics metrics = new InMemoryRosterMetrics();
    final CatalogService catalogs;
    final RosterService roster;
    final BookingService bookings;
    final InProcessEventBus bus = new InProcessEventBus();
    final List<OutboxEvent> delivered = new CopyOnWriteArrayList<>();
    final OutboxRelay relay;
    final ReconciliationService recon;
    final InMemoryRunStore runs;

    Harness(Path dataDir) {
        this(new InMemoryStore(), new InMemoryRunStore(), new MutableClock(Instant.parse("2026-10-31T00:00:00Z")), dataDir, OutboxRelay.Hooks.NONE);
    }

    Harness(InMemoryStore store, InMemoryRunStore runs, MutableClock clock, Path dataDir, OutboxRelay.Hooks hooks) {
        this.store = store;
        this.runs = runs;
        this.clock = clock;
        this.catalogs = new CatalogService(store);
        catalogs.put(T, SyntheticRoster.OFFICES, SyntheticRoster.SHIFTS);
        catalogs.put("globex", SyntheticRoster.OFFICES, SyntheticRoster.SHIFTS);
        this.roster = new RosterService(store, catalogs, metrics, clock);
        this.bookings = new BookingService(store, store, clock);
        bus.subscribe(delivered::add).subscribe(new DeactivationSideEffects(store, clock));
        EventPublisher pub = bus;
        this.relay = new OutboxRelay(store, pub, clock, OutboxRelay.Listener.NONE, hooks);
        this.recon = new ReconciliationService(store, roster, catalogs, runs, metrics, clock, dataDir, SafetyBrake.defaults());
    }

    @Override
    public void close() {
        recon.close();
    }
}
