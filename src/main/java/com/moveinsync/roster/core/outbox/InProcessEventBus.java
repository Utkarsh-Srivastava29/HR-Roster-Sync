package com.moveinsync.roster.core.outbox;

import com.moveinsync.roster.core.booking.EventSubscriber;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans each event out to in-process subscribers. If any subscriber throws, publish fails and
 * the relay retries the event later - subscribers are idempotent, so the ones that already
 * succeeded simply no-op on the retry.
 */
public final class InProcessEventBus implements EventPublisher {

    private final List<EventSubscriber> subscribers = new CopyOnWriteArrayList<>();

    public InProcessEventBus subscribe(EventSubscriber s) {
        subscribers.add(s);
        return this;
    }

    @Override
    public void publish(OutboxEvent event) throws Exception {
        for (EventSubscriber s : subscribers) s.onEvent(event);
    }
}
