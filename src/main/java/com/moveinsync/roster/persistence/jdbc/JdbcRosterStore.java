package com.moveinsync.roster.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.core.booking.Booking;
import com.moveinsync.roster.core.booking.BookingStore;
import com.moveinsync.roster.core.booking.Route;
import com.moveinsync.roster.core.domain.ChangeSource;
import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeStatus;
import com.moveinsync.roster.core.domain.HrProfile;
import com.moveinsync.roster.core.domain.TransportProfile;
import com.moveinsync.roster.core.outbox.OutboxDraft;
import com.moveinsync.roster.core.outbox.OutboxEvent;
import com.moveinsync.roster.core.store.Buckets;
import com.moveinsync.roster.core.store.CatalogStore;
import com.moveinsync.roster.core.store.DigestRow;
import com.moveinsync.roster.core.store.RosterStore;
import com.moveinsync.roster.core.validation.TenantCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Postgres implementation.
 *
 * <ul>
 *   <li>Per-employee serialisation: {@code pg_advisory_xact_lock} on a hash of (tenant, employee)
 *       taken first in the transaction. Works for rows that do not exist yet (a CREATE racing an
 *       UPDATE), which a row lock cannot do.</li>
 *   <li>Outbox: the employee upsert and the outbox inserts are one database transaction.</li>
 *   <li>Bucket digests: {@code bit_xor(digest) GROUP BY bucket} (Postgres 14+), computed by the
 *       database; the application only ever holds 4,096 numbers.</li>
 * </ul>
 */
public class JdbcRosterStore implements RosterStore, CatalogStore, BookingStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcRosterStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ employees + outbox

    @Override
    public <T> T inEmployeeTransaction(String tenantId, String employeeId, EmployeeWork<T> work) {
        return tx.execute(status -> {
            lock("emp/" + tenantId + "/" + employeeId);
            Optional<Employee> current = find(tenantId, employeeId);
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
            if (saved[0] != null) upsert(saved[0]);
            for (OutboxDraft d : emitted) insertOutbox(d);
            return result;
        });
    }

    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> { }, fnv(key));
    }

    private void upsert(Employee e) {
        HrProfile p = e.hr();
        int bucket = Buckets.of(e.employeeId());
        jdbc.update("""
                INSERT INTO employee (tenant_id, employee_id, status, name, email, phone, manager_id, office_code, shift_code,
                    hr_version, hr_source_time, hr_hash, pickup_point, special_assistance, transport_version, bucket, digest, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                    status = EXCLUDED.status, name = EXCLUDED.name, email = EXCLUDED.email, phone = EXCLUDED.phone,
                    manager_id = EXCLUDED.manager_id, office_code = EXCLUDED.office_code, shift_code = EXCLUDED.shift_code,
                    hr_version = EXCLUDED.hr_version, hr_source_time = EXCLUDED.hr_source_time, hr_hash = EXCLUDED.hr_hash,
                    pickup_point = EXCLUDED.pickup_point, special_assistance = EXCLUDED.special_assistance,
                    transport_version = EXCLUDED.transport_version, bucket = EXCLUDED.bucket, digest = EXCLUDED.digest,
                    updated_at = EXCLUDED.updated_at
                """,
                e.tenantId(), e.employeeId(), e.status().name(), p.name(), p.email(), p.phone(), p.managerId(), p.officeCode(),
                p.shiftCode(), e.hrVersion(), ts(e.hrSourceTime()), e.hrHash(), e.transport().pickupPoint(),
                toJson(e.transport().specialAssistance()), e.transportVersion(), bucket,
                Buckets.digest(e.employeeId(), e.hrHash()), ts(e.updatedAt()));
    }

    private void insertOutbox(OutboxDraft d) {
        jdbc.update("""
                INSERT INTO outbox (event_id, tenant_id, employee_id, change_type, changed_fields, hr_version, transport_version,
                    source, occurred_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, d.eventId(), d.tenantId(), d.employeeId(), d.type().name(), String.join(",", d.changedFields()),
                d.hrVersion(), d.transportVersion(), d.source().name(), ts(d.occurredAt()));
    }

    private final RowMapper<Employee> employeeRow = (rs, i) -> new Employee(
            rs.getString("tenant_id"), rs.getString("employee_id"), EmployeeStatus.valueOf(rs.getString("status")),
            new HrProfile(rs.getString("name"), rs.getString("email"), rs.getString("phone"), rs.getString("manager_id"),
                    rs.getString("office_code"), rs.getString("shift_code")),
            rs.getLong("hr_version"), rs.getTimestamp("hr_source_time").toInstant(), rs.getLong("hr_hash"),
            new TransportProfile(rs.getString("pickup_point"), fromJson(rs.getString("special_assistance"))),
            rs.getLong("transport_version"), rs.getTimestamp("updated_at").toInstant());

    @Override
    public Optional<Employee> find(String tenantId, String employeeId) {
        return jdbc.query("SELECT * FROM employee WHERE tenant_id = ? AND employee_id = ?", employeeRow, tenantId, employeeId)
                .stream().findFirst();
    }

    @Override
    public List<Employee> list(String tenantId, String after, int limit) {
        return after == null
                ? jdbc.query("SELECT * FROM employee WHERE tenant_id = ? ORDER BY employee_id LIMIT ?", employeeRow, tenantId, limit)
                : jdbc.query("SELECT * FROM employee WHERE tenant_id = ? AND employee_id > ? ORDER BY employee_id LIMIT ?",
                employeeRow, tenantId, after, limit);
    }

    @Override
    public long countActive(String tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'", Long.class, tenantId);
        return n == null ? 0 : n;
    }

    @Override
    public long[] bucketDigests(String tenantId) {
        long[] d = new long[Buckets.COUNT];
        jdbc.query("SELECT bucket, bit_xor(digest) AS d FROM employee WHERE tenant_id = ? AND status = 'ACTIVE' GROUP BY bucket",
                rs -> { d[rs.getInt("bucket")] = rs.getLong("d"); }, tenantId);
        return d;
    }

    @Override
    public void scanBuckets(String tenantId, BitSet buckets, Consumer<DigestRow> sink) {
        Integer[] ids = buckets.stream().boxed().toArray(Integer[]::new);
        if (ids.length == 0) return;
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT employee_id, bucket, hr_hash, hr_version, status, hr_source_time
                    FROM employee WHERE tenant_id = ? AND bucket = ANY(?)
                    """);
            ps.setString(1, tenantId);
            ps.setArray(2, con.createArrayOf("integer", ids));
            ps.setFetchSize(2_000);
            return ps;
        }, rs -> {
            sink.accept(new DigestRow(rs.getString("employee_id"), rs.getInt("bucket"), rs.getLong("hr_hash"),
                    rs.getLong("hr_version"), "ACTIVE".equals(rs.getString("status")), rs.getTimestamp("hr_source_time").toInstant()));
        });
    }

    // ------------------------------------------------------------------ outbox

    private OutboxEvent outboxRow(ResultSet rs) throws SQLException {
        String fields = rs.getString("changed_fields");
        Timestamp pub = rs.getTimestamp("published_at");
        return new OutboxEvent(rs.getLong("seq"), new OutboxDraft(rs.getString("event_id"), rs.getString("tenant_id"),
                rs.getString("employee_id"), ChangeType.valueOf(rs.getString("change_type")),
                fields.isEmpty() ? List.of() : List.of(fields.split(",")), rs.getLong("hr_version"),
                rs.getLong("transport_version"), ChangeSource.valueOf(rs.getString("source")),
                rs.getTimestamp("occurred_at").toInstant()), pub == null ? null : pub.toInstant(), rs.getInt("attempts"));
    }

    @Override
    public List<OutboxEvent> pollUnpublished(int limit) {
        return jdbc.query("SELECT * FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT ?", (rs, i) -> outboxRow(rs), limit);
    }

    @Override
    public void markPublished(List<Long> seqs, Instant at) {
        if (seqs.isEmpty()) return;
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE outbox SET published_at = ?, attempts = attempts + 1 WHERE seq = ANY(?)");
            ps.setTimestamp(1, ts(at));
            ps.setArray(2, con.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            return ps;
        });
    }

    @Override
    public void recordPublishFailure(long seq) {
        jdbc.update("UPDATE outbox SET attempts = attempts + 1 WHERE seq = ?", seq);
    }

    @Override
    public long unpublishedCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<Instant> oldestUnpublishedAt() {
        Timestamp t = jdbc.queryForObject("SELECT min(occurred_at) FROM outbox WHERE published_at IS NULL", Timestamp.class);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    @Override
    public List<OutboxEvent> changesAfter(String tenantId, long afterSeq, int limit) {
        return jdbc.query("SELECT * FROM outbox WHERE tenant_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                (rs, i) -> outboxRow(rs), tenantId, afterSeq, limit);
    }

    // ------------------------------------------------------------------ catalog

    @Override
    public Optional<TenantCatalog> catalog(String tenantId) {
        return jdbc.query("SELECT offices, shifts FROM catalog WHERE tenant_id = ?", (rs, i) ->
                new TenantCatalog(Set.copyOf(fromJson(rs.getString("offices"))), Set.copyOf(fromJson(rs.getString("shifts")))),
                tenantId).stream().findFirst();
    }

    @Override
    public void putCatalog(String tenantId, TenantCatalog c) {
        jdbc.update("""
                INSERT INTO catalog (tenant_id, offices, shifts) VALUES (?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET offices = EXCLUDED.offices, shifts = EXCLUDED.shifts
                """, tenantId, toJson(List.copyOf(c.officeCodes())), toJson(List.copyOf(c.shiftCodes())));
    }

    // ------------------------------------------------------------------ bookings

    @Override
    public <T> T inBookingTransaction(String tenantId, BookingWork<T> work) {
        return tx.execute(status -> {
            lock("booking/" + tenantId);
            return work.run(new BookingTx() {
                @Override public boolean markProcessed(String consumer, String eventId) {
                    return jdbc.update("INSERT INTO processed_event (consumer, event_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                            consumer, eventId) == 1;
                }
                @Override public List<Booking> futureBookings(String employeeId, Instant after) {
                    return jdbc.query("""
                            SELECT * FROM booking WHERE tenant_id = ? AND employee_id = ? AND status = 'BOOKED' AND pickup_at > ?
                            """, bookingRow, tenantId, employeeId, ts(after));
                }
                @Override public Optional<Route> route(String routeId) {
                    return JdbcRosterStore.this.route(tenantId, routeId);
                }
                @Override public void saveRoute(Route r) {
                    jdbc.update("""
                            INSERT INTO route (tenant_id, route_id, capacity, seats_taken) VALUES (?,?,?,?)
                            ON CONFLICT (tenant_id, route_id) DO UPDATE SET capacity = EXCLUDED.capacity, seats_taken = EXCLUDED.seats_taken
                            """, r.tenantId(), r.routeId(), r.capacity(), r.seatsTaken());
                }
                @Override public void saveBooking(Booking b) {
                    jdbc.update("""
                            INSERT INTO booking (tenant_id, booking_id, employee_id, route_id, pickup_at, status) VALUES (?,?,?,?,?,?)
                            ON CONFLICT (tenant_id, booking_id) DO UPDATE SET status = EXCLUDED.status
                            """, b.tenantId(), b.bookingId(), b.employeeId(), b.routeId(), ts(b.pickupAt()), b.status().name());
                }
            });
        });
    }

    private final RowMapper<Booking> bookingRow = (rs, i) -> new Booking(rs.getString("tenant_id"), rs.getString("booking_id"),
            rs.getString("employee_id"), rs.getString("route_id"), rs.getTimestamp("pickup_at").toInstant(),
            Booking.Status.valueOf(rs.getString("status")));

    @Override
    public List<Booking> bookingsOf(String tenantId, String employeeId) {
        return jdbc.query("SELECT * FROM booking WHERE tenant_id = ? AND employee_id = ? ORDER BY pickup_at", bookingRow, tenantId, employeeId);
    }

    @Override
    public Optional<Route> route(String tenantId, String routeId) {
        return jdbc.query("SELECT * FROM route WHERE tenant_id = ? AND route_id = ?", (rs, i) ->
                        new Route(rs.getString("tenant_id"), rs.getString("route_id"), rs.getInt("capacity"), rs.getInt("seats_taken")),
                tenantId, routeId).stream().findFirst();
    }

    // ------------------------------------------------------------------ helpers

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }

    private String toJson(List<String> l) {
        try { return json.writeValueAsString(l); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private List<String> fromJson(String s) {
        if (s == null || s.isBlank()) return List.of();
        try { return json.readValue(s, new TypeReference<List<String>>() { }); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) { h ^= (b & 0xff); h *= 0x100000001b3L; }
        return h;
    }
}
