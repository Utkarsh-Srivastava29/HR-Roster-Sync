package com.moveinsync.roster.persistence.memory;

import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.booking.BookingStore;
import com.moveinsync.roster.core.booking.Route;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.outbox.OutboxDraft;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import com.moveinsync.roster.core.store.Buckets;
import com.moveinsync.roster.core.store.CatalogStore;
import com.moveinsync.roster.core.store.DigestRow;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.core.validation.TenantCatalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * In-memory implementation with the same transactional guarantees as the Postgres one:
 * per-employee serialisation (striped locks) and all-or-nothing commit of the employee row
 * plus its outbox events. Used by the tests and the benchmark; it plays the role of the
 * database, so it survives a simulated <i>application</i> crash just like Postgres would.
 */
public final class InMemoryStore implements RosterStore, CatalogStore, BookingStore {

    private static final int STRIPES = 1024;

    private final Map<String, ConcurrentSkipListMap<String, Employee>> employees = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentSkipListMap<Long, OutboxEvent> outbox = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListSet<Long> unpublished = new ConcurrentSkipListSet<>();
    private final Map<String, TenantCatalog> catalogs = new ConcurrentHashMap<>();
    private final Map<String, TenantBookings> bookings = new ConcurrentHashMap<>();

    public InMemoryStore() {
        for (int i = 0; i < STRIPES; i++) stripes[i] = new ReentrantLock();
    }

    private ConcurrentSkipListMap<String, Employee> tenant(String t) {
        return employees.computeIfAbsent(t, k -> new ConcurrentSkipListMap<>());
    }

    // ------------------------------------------------------------------ employees + outbox

    @Override
    public <T> T inEmployeeTransaction(String tenantId, String employeeId, EmployeeWork<T> work) {
        ReentrantLock lock = stripes[Math.floorMod((tenantId + "/" + employeeId).hashCode(), STRIPES)];
        lock.lock();
        try {
            Optional<Employee> current = Optional.ofNullable(tenant(tenantId).get(employeeId));
            Employee[] saved = new Employee[1];
            List<OutboxDraft> emitted = new ArrayList<>();
            T result = work.run(new EmployeeTx() {
                @Override public Optional<Employee> current() { return saved[0] != null ? Optional.of(saved[0]) : current; }
                @Override public void save(Employee e) {
                    if (!e.tenantId().equals(tenantId) || !e.employeeId().equals(employeeId))
                        throw new IllegalArgumentException("transaction is for another employee");
                    saved[0] = e;
                }
                @Override public void emit(OutboxDraft d) { emitted.add(d); }
            });
            // commit: nothing below can fail half-way
            if (saved[0] != null) tenant(tenantId).put(employeeId, saved[0]);
            for (OutboxDraft d : emitted) {
                long s = seq.incrementAndGet();
                outbox.put(s, new OutboxEvent(s, d, null, 0));
                unpublished.add(s);
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Employee> find(String tenantId, String employeeId) {
        return Optional.ofNullable(tenant(tenantId).get(employeeId));
    }

    @Override
    public List<Employee> list(String tenantId, String afterEmployeeId, int limit) {
        var m = tenant(tenantId);
        var view = afterEmployeeId == null ? m : m.tailMap(afterEmployeeId, false);
        return view.values().stream().limit(limit).toList();
    }

    @Override
    public long countActive(String tenantId) {
        return tenant(tenantId).values().stream().filter(Employee::active).count();
    }

    @Override
    public long[] bucketDigests(String tenantId) {
        long[] d = new long[Buckets.COUNT];
        for (Employee e : tenant(tenantId).values())
            if (e.active()) d[Buckets.of(e.employeeId())] ^= Buckets.digest(e.employeeId(), e.hrHash());
        return d;
    }

    @Override
    public void scanBuckets(String tenantId, BitSet buckets, Consumer<DigestRow> sink) {
        for (Employee e : tenant(tenantId).values()) {
            int b = Buckets.of(e.employeeId());
            if (buckets.get(b)) sink.accept(new DigestRow(e.employeeId(), b, e.hrHash(), e.hrVersion(), e.active(), e.hrSourceTime()));
        }
    }

    @Override
    public List<OutboxEvent> pollUnpublished(int limit) {
        List<OutboxEvent> out = new ArrayList<>(Math.min(limit, 1024));
        for (Long s : unpublished) {
            if (out.size() >= limit) break;
            OutboxEvent e = outbox.get(s);
            if (e != null) out.add(e);
        }
        return out;
    }

    @Override
    public void markPublished(List<Long> seqs, Instant at) {
        for (Long s : seqs) {
            outbox.computeIfPresent(s, (k, e) -> new OutboxEvent(e.seq(), e.draft(), at, e.attempts() + 1));
            unpublished.remove(s);
        }
    }

    @Override
    public void recordPublishFailure(long s) {
        outbox.computeIfPresent(s, (k, e) -> new OutboxEvent(e.seq(), e.draft(), null, e.attempts() + 1));
    }

    @Override
    public long unpublishedCount() { return unpublished.size(); }

    @Override
    public Optional<Instant> oldestUnpublishedAt() {
        Long first = unpublished.isEmpty() ? null : unpublished.first();
        return first == null ? Optional.empty() : Optional.ofNullable(outbox.get(first)).map(e -> e.draft().occurredAt());
    }

    @Override
    public List<OutboxEvent> changesAfter(String tenantId, long afterSeq, int limit) {
        List<OutboxEvent> out = new ArrayList<>();
        for (OutboxEvent e : outbox.tailMap(afterSeq, false).values()) {
            if (!e.tenantId().equals(tenantId)) continue;
            out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Test helper: every outbox event ever written. */
    public List<OutboxEvent> allOutboxEvents() { return new ArrayList<>(outbox.values()); }

    // ------------------------------------------------------------------ catalog

    @Override
    public Optional<TenantCatalog> catalog(String tenantId) { return Optional.ofNullable(catalogs.get(tenantId)); }

    @Override
    public void putCatalog(String tenantId, TenantCatalog c) { catalogs.put(tenantId, c); }

    // ------------------------------------------------------------------ bookings

    private static final class TenantBookings {
        final Map<String, Booking> byId = new HashMap<>();
        final Map<String, Route> routes = new HashMap<>();
        final Set<String> processed = new HashSet<>();
    }

    private TenantBookings tb(String t) { return bookings.computeIfAbsent(t, k -> new TenantBookings()); }

    @Override
    public <T> T inBookingTransaction(String tenantId, BookingWork<T> work) {
        TenantBookings b = tb(tenantId);
        synchronized (b) {
            Map<String, Booking> stagedBookings = new HashMap<>();
            Map<String, Route> stagedRoutes = new HashMap<>();
            Set<String> stagedProcessed = new HashSet<>();
            T result = work.run(new BookingTx() {
                @Override public boolean markProcessed(String consumer, String eventId) {
                    String k = consumer + "|" + eventId;
                    if (b.processed.contains(k) || stagedProcessed.contains(k)) return false;
                    stagedProcessed.add(k);
                    return true;
                }
                @Override public List<Booking> futureBookings(String employeeId, Instant after) {
                    return b.byId.values().stream()
                            .map(x -> stagedBookings.getOrDefault(x.bookingId(), x))
                            .filter(x -> x.employeeId().equals(employeeId) && x.status() == Booking.Status.BOOKED
                                    && x.pickupAt().isAfter(after)).toList();
                }
                @Override public Optional<Route> route(String routeId) {
                    return Optional.ofNullable(stagedRoutes.getOrDefault(routeId, b.routes.get(routeId)));
                }
                @Override public void saveRoute(Route r) { stagedRoutes.put(r.routeId(), r); }
                @Override public void saveBooking(Booking x) { stagedBookings.put(x.bookingId(), x); }
            });
            b.byId.putAll(stagedBookings);
            b.routes.putAll(stagedRoutes);
            b.processed.addAll(stagedProcessed);
            return result;
        }
    }

    @Override
    public List<Booking> bookingsOf(String tenantId, String employeeId) {
        TenantBookings b = tb(tenantId);
        synchronized (b) {
            return b.byId.values().stream().filter(x -> x.employeeId().equals(employeeId)).toList();
        }
    }

    @Override
    public Optional<Route> route(String tenantId, String routeId) {
        TenantBookings b = tb(tenantId);
        synchronized (b) {
            return Optional.ofNullable(b.routes.get(routeId));
        }
    }
}
