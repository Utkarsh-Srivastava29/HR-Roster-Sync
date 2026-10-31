package com.moveinsync.roster.service;

import com.moveinsync.roster.core.outbox.EventPublisher;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import com.moveinsync.roster.core.outbox.OutboxRelay;
import com.moveinsync.roster.core.store.RosterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the outbox relay in the background. Exposes the two numbers on-call cares about:
 * outbox backlog (queue depth) and age of the oldest unpublished event (lag).
 */
public class RelayWorker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RelayWorker.class);

    private final OutboxRelay relay;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "outbox-relay");
        t.setDaemon(true);
        return t;
    });

    public RelayWorker(RosterStore store, EventPublisher publisher, RosterMetrics metrics, Clock clock, OutboxRelay.Hooks hooks) {
        this.relay = new OutboxRelay(store, publisher, clock, new OutboxRelay.Listener() {
            @Override public void published(OutboxEvent e) {
                metrics.published(Duration.between(e.draft().occurredAt(), clock.instant()).toMillis());
            }
            @Override public void failed(OutboxEvent e, Exception ex) {
                metrics.publishFailed();
                LOG.warn("publish failed seq={} employeeId={} attempts={} cause={}",
                        e.seq(), e.employeeId(), e.attempts() + 1, ex.getClass().getSimpleName());
            }
        }, hooks);
        metrics.registerGauge("outbox.backlog", store::unpublishedCount);
        metrics.registerGauge("outbox.oldest.age.seconds", () -> store.oldestUnpublishedAt()
                .map(t -> Duration.between(t, clock.instant()).toMillis() / 1000.0).orElse(0.0));
    }

    public OutboxRelay relay() { return relay; }

    public void start(long periodMillis) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relay.drainAll(500);
            } catch (Throwable t) {
                LOG.error("relay round failed: {}", t.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
