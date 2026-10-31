package com.moveinsync.roster.observability;

import com.moveinsync.roster.service.RosterMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Exported at {@code /actuator/prometheus} (management port). The 3 AM dashboard:
 * <pre>
 * roster_records_total{tenant,source,outcome}        throughput and error rate (outcome=REJECTED / CONFLICT)
 * roster_rejected_fields_total{tenant,field,code}     which field is broken in the client's feed
 * roster_ingest_seconds{tenant}                       p50 / p99 per record
 * roster_outbox_backlog                               events committed but not yet published (queue depth)
 * roster_outbox_oldest_age_seconds                    lag of the oldest unpublished event
 * roster_outbox_publish_lag_seconds                   commit-to-publish latency
 * roster_outbox_publish_failures_total                retries happening
 * roster_reconciliation_runs_total{tenant,status}     COMPLETED / BRAKED / FAILED
 * roster_reconciliation_brake_total{tenant}           page someone
 * roster_reconciliation_queue_depth                   runs waiting
 * </pre>
 */
@Component
public class MicrometerRosterMetrics implements RosterMetrics {

    private final MeterRegistry reg;

    public MicrometerRosterMetrics(MeterRegistry reg) {
        this.reg = reg;
    }

    @Override
    public void record(String tenant, String source, String outcome) {
        Counter.builder("roster.records").tags("tenant", tenant, "source", source, "outcome", outcome).register(reg).increment();
    }

    @Override
    public void rejectedField(String tenant, String field, String code) {
        Counter.builder("roster.rejected.fields").tags("tenant", tenant, "field", field, "code", code).register(reg).increment();
    }

    @Override
    public void ingestLatency(String tenant, long micros) {
        Timer.builder("roster.ingest").tag("tenant", tenant).publishPercentiles(0.5, 0.99)
                .register(reg).record(Duration.ofNanos(micros * 1_000));
    }

    @Override
    public void published(long lagMillis) {
        Timer.builder("roster.outbox.publish.lag").publishPercentiles(0.5, 0.99).register(reg).record(Duration.ofMillis(lagMillis));
    }

    @Override
    public void publishFailed() { Counter.builder("roster.outbox.publish.failures").register(reg).increment(); }

    @Override
    public void relayError() { Counter.builder("roster.outbox.relay.errors").register(reg).increment(); }

    @Override
    public void reconciliationRun(String tenant, String status, long millis) {
        Timer.builder("roster.reconciliation.runs").tags("tenant", tenant, "status", status).register(reg).record(Duration.ofMillis(millis));
    }

    @Override
    public void brakeTripped(String tenant) {
        Counter.builder("roster.reconciliation.brake").tag("tenant", tenant).register(reg).increment();
    }

    @Override
    public void registerGauge(String name, Supplier<Number> value) {
        Gauge.builder("roster." + name, value, s -> s.get().doubleValue()).strongReference(true).register(reg);
    }
}
