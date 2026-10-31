package com.moveinsync.roster.service;

import java.util.function.Supplier;

/**
 * Counters for on-call. Implemented with Micrometer in the Spring layer and in memory for tests.
 * Tags carry tenant ids and enum names only - never personal data.
 */
public interface RosterMetrics {
    /** One record offered: source (HR_API, HR_EXPORT, ...) and outcome (APPLIED, STALE, ...). */
    void record(String tenant, String source, String outcome);
    void rejectedField(String tenant, String field, String code);
    void ingestLatency(String tenant, long micros);
    void published(long lagMillis);
    void publishFailed();
    void relayError();
    void reconciliationRun(String tenant, String status, long millis);
    void brakeTripped(String tenant);
    void registerGauge(String name, Supplier<Number> value);
}
