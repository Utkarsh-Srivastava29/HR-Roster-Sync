package com.moveinsync.roster.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Plain-Java metrics for tests and the benchmark. */
public final class InMemoryRosterMetrics implements RosterMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<>();
    private final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

    private void inc(String k) { counters.computeIfAbsent(k, x -> new AtomicLong()).incrementAndGet(); }

    public long count(String k) {
        AtomicLong a = counters.get(k);
        return a == null ? 0 : a.get();
    }

    public Number gauge(String name) { return gauges.get(name).get(); }

    public List<Long> latenciesMicros() { synchronized (latencies) { return new ArrayList<>(latencies); } }

    @Override public void record(String t, String source, String outcome) { inc(source + "." + outcome); inc(outcome); }
    @Override public void rejectedField(String t, String field, String code) { inc("rejected." + field + "." + code); }
    @Override public void ingestLatency(String t, long micros) { latencies.add(micros); }
    @Override public void published(long lagMillis) { inc("published"); }
    @Override public void publishFailed() { inc("publish.failed"); }
    @Override public void relayError() { inc("relay.error"); }
    @Override public void reconciliationRun(String t, String status, long millis) { inc("run." + status); }
    @Override public void brakeTripped(String t) { inc("brake"); }
    @Override public void registerGauge(String name, Supplier<Number> value) { gauges.put(name, value); }
}
