package com.moveinsync.roster.core.outbox;

import java.time.Instant;

/**
 * A stored change event. {@code seq} is assigned by the store at commit; per employee it
 * increases in commit order. {@code publishedAt} is null until the relay has delivered it.
 */
public record OutboxEvent(long seq, OutboxDraft draft, Instant publishedAt, int attempts) {
    public String eventId() { return draft.eventId(); }
    public String tenantId() { return draft.tenantId(); }
    public String employeeId() { return draft.employeeId(); }
}
