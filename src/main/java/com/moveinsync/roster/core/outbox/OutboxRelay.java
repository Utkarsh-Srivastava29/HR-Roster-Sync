package com.moveinsync.roster.core.outbox;

import com.moveinsync.roster.core.store.RosterStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves committed change events from the outbox to the publisher.
 *
 * <p>Guarantees: every committed change is eventually published (it stays in the outbox until
 * delivery is confirmed); events of one employee are published in commit order (after a failure,
 * that employee's later events wait for the next round); delivery is at-least-once (a crash
 * between publish and mark re-delivers, and consumers de-duplicate on eventId).
 */
public final class OutboxRelay {

    /** Observability callbacks. */
    public interface Listener {
        Listener NONE = new Listener() {};
        default void published(OutboxEvent e) {}
        default void failed(OutboxEvent e, Exception ex) {}
    }

    /** Failure-injection seam: throw here to simulate the process dying after publish, before mark. */
    @FunctionalInterface
    public interface Hooks {
        Hooks NONE = e -> {};
        void afterPublishBeforeMark(OutboxEvent e);
    }

    private final RosterStore store;
    private final EventPublisher publisher;
    private final Clock clock;
    private final Listener listener;
    private final Hooks hooks;

    public OutboxRelay(RosterStore store, EventPublisher publisher, Clock clock, Listener listener, Hooks hooks) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.listener = listener;
        this.hooks = hooks;
    }

    /** One round. @return number of events published */
    public int drainOnce(int batchSize) {
        List<OutboxEvent> batch = store.pollUnpublished(batchSize);
        List<Long> done = new ArrayList<>();
        Set<String> blocked = new HashSet<>();
        boolean crashed = false;
        try {
            for (OutboxEvent e : batch) {
                String key = e.tenantId() + "/" + e.employeeId();
                if (blocked.contains(key)) continue;          // keep per-employee order
                try {
                    publisher.publish(e);
                    hooks.afterPublishBeforeMark(e);
                    done.add(e.seq());
                    listener.published(e);
                } catch (RuntimeException crash) {
                    if (crash instanceof SimulatedCrash) { crashed = true; throw crash; }
                    fail(e, crash, blocked, key);
                } catch (Exception ex) {
                    fail(e, ex, blocked, key);
                }
            }
        } finally {
            // a real crash runs no code here: nothing is marked, everything above is re-delivered
            if (!crashed && !done.isEmpty()) store.markPublished(done, clock.instant());
        }
        return done.size();
    }

    /** Drain until the outbox is empty or a round publishes nothing. */
    public int drainAll(int batchSize) {
        int total = 0, n;
        while ((n = drainOnce(batchSize)) > 0) total += n;
        return total;
    }

    private void fail(OutboxEvent e, Exception ex, Set<String> blocked, String key) {
        blocked.add(key);
        store.recordPublishFailure(e.seq());
        listener.failed(e, ex);
    }

    /** Thrown by tests to model the process dying mid-relay; nothing after it runs. */
    public static final class SimulatedCrash extends RuntimeException {
        public SimulatedCrash(String msg) { super(msg); }
    }
}
