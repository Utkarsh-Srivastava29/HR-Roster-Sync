package com.moveinsync.roster.core.outbox;

/**
 * Delivers a change event downstream (in-process subscribers here; Kafka/SNS in production).
 * Delivery is at-least-once: the same event may be delivered again after a crash, so
 * subscribers must be idempotent on {@code eventId}.
 */
public interface EventPublisher {
    void publish(OutboxEvent event) throws Exception;
}
