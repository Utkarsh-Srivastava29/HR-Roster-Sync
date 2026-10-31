package com.moveinsync.roster.core.booking;

import com.moveinsync.roster.core.outbox.OutboxEvent;

/** Something downstream that reacts to employee change events. Must be idempotent. */
public interface EventSubscriber {
    void onEvent(OutboxEvent event) throws Exception;
}
