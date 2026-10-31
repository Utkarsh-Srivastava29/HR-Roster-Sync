package com.moveinsync.roster.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.booking.BookingStore;
import com.moveinsync.roster.core.booking.DeactivationSideEffects;
import com.moveinsync.roster.core.outbox.InProcessEventBus;
import com.moveinsync.roster.core.outbox.OutboxRelay;
import com.moveinsync.roster.core.reconcile.SafetyBrake;
import com.moveinsync.roster.core.store.CatalogStore;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.persistence.jdbc.JdbcRosterStore;
import com.moveinsync.roster.persistence.jdbc.JdbcRunStore;
import com.moveinsync.roster.persistence.memory.InMemoryRunStore;
import com.moveinsync.roster.persistence.memory.InMemoryStore;
import com.moveinsync.roster.service.BookingService;
import com.moveinsync.roster.service.CatalogService;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.service.RelayWorker;
import com.moveinsync.roster.service.RosterMetrics;
import com.moveinsync.roster.service.RosterService;
import com.moveinsync.roster.service.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

/**
 * Wires the plain-Java core and services. {@code roster.store=jdbc} uses Postgres (docker compose);
 * {@code roster.store=memory} needs nothing installed (tests, quick local run).
 */
@Configuration
public class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

    /** Holder so the store-specific beans can be chosen at runtime from one property. */
    public record Stores(RosterStore roster, CatalogStore catalog, BookingStore bookings, RunStore runs) {}

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Stores stores(RosterProperties props, ObjectMapper json, ObjectProvider<JdbcTemplate> jdbc,
                  ObjectProvider<TransactionTemplate> tx) {
        if ("jdbc".equalsIgnoreCase(props.store())) {
            JdbcRosterStore s = new JdbcRosterStore(jdbc.getObject(), tx.getObject(), json);
            LOG.info("using the Postgres store");
            return new Stores(s, s, s, new JdbcRunStore(jdbc.getObject(), json));
        }
        LOG.warn("using the in-memory store: data is lost on restart (set roster.store=jdbc for Postgres)");
        InMemoryStore m = new InMemoryStore();
        return new Stores(m, m, m, new InMemoryRunStore());
    }

    @Bean
    CatalogService catalogService(Stores s) {
        return new CatalogService(s.catalog());
    }

    @Bean
    RosterService rosterService(Stores s, CatalogService c, RosterMetrics m, Clock clock) {
        return new RosterService(s.roster(), c, m, clock);
    }

    @Bean
    BookingService bookingService(Stores s, Clock clock) {
        return new BookingService(s.bookings(), s.roster(), clock);
    }

    @Bean(destroyMethod = "close")
    ReconciliationService reconciliationService(Stores s, RosterService r, CatalogService c, RosterMetrics m, Clock clock,
                                                RosterProperties props) {
        RosterProperties.Brake b = props.brake();
        SafetyBrake brake = b == null ? SafetyBrake.defaults() : new SafetyBrake(b.maxMissingRatio(), b.requireTrailer(), b.minActiveForRatio());
        ReconciliationService svc = new ReconciliationService(s.roster(), r, c, s.runs(), m, clock, Path.of(props.dataDir()), brake);
        m.registerGauge("reconciliation.queue.depth", svc::queueDepth);
        return svc;
    }

    @Bean(destroyMethod = "close")
    RelayWorker relayWorker(Stores s, RosterMetrics m, Clock clock, RosterProperties props) {
        InProcessEventBus bus = new InProcessEventBus()
                .subscribe(new DeactivationSideEffects(s.bookings(), clock))
                .subscribe(e -> LOG.info("change event published seq={} type={} employeeId={}", e.seq(), e.draft().type(), e.employeeId()));
        RelayWorker w = new RelayWorker(s.roster(), bus, m, clock, OutboxRelay.Hooks.NONE);
        w.start(props.relayPeriodMs() <= 0 ? 200 : props.relayPeriodMs());
        return w;
    }

    /** After a crash: resume reconciliations that were in flight. Then seed demo reference data. */
    @Bean
    ApplicationRunner startup(ReconciliationService recon, CatalogService catalogs, BookingService bookings, RosterProperties props,
                              Stores stores) {
        return args -> {
            int resumed = recon.recoverInterrupted();
            if (resumed > 0) LOG.info("resumed {} interrupted reconciliation run(s)", resumed);
            if (props.demoSeed()) {
                for (String t : Set.of("acme", "globex")) {
                    if (stores.catalog().catalog(t).isEmpty())
                        catalogs.put(t, Set.of("BLR1", "BLR2", "HYD1", "PUN1", "CHN1"), Set.of("GEN", "EVE", "NIGHT"));
                }
                if (stores.bookings().route("acme", "R1").isEmpty()) bookings.upsertRoute("acme", "R1", 4);
                LOG.info("demo reference data ready for tenants acme and globex");
            }
        };
    }
}
