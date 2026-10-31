package com.moveinsync.roster.core.store;

import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.outbox.OutboxDraft;
import com.moveinsync.roster.core.outbox.OutboxEvent;

import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Employee records and the transactional outbox. Implemented in memory (tests, benchmark) and
 * on Postgres (production). Every method takes the tenant id; nothing ever crosses tenants.
 */
public interface RosterStore {

    /**
     * Run {@code work} for one employee, serialised with every other transaction on the same
     * employee. Whatever it saves and emits is committed together, or not at all if it throws -
     * the employee row and its change events can never disagree.
     */
    <T> T inEmployeeTransaction(String tenantId, String employeeId, EmployeeWork<T> work);

    @FunctionalInterface
    interface EmployeeWork<T> { T run(EmployeeTx tx); }

    interface EmployeeTx {
        Optional<Employee> current();
        void save(Employee e);
        void emit(OutboxDraft event);
    }

    Optional<Employee> find(String tenantId, String employeeId);

    List<Employee> list(String tenantId, String afterEmployeeId, int limit);

    long countActive(String tenantId);

    /** XOR digest per bucket over active employees, length {@link Buckets#COUNT}. */
    long[] bucketDigests(String tenantId);

    /** Every employee (active or not) in the given buckets. */
    void scanBuckets(String tenantId, BitSet buckets, Consumer<DigestRow> sink);

    // ---- outbox ----

    /** Oldest unpublished events first, across tenants. */
    List<OutboxEvent> pollUnpublished(int limit);

    void markPublished(List<Long> seqs, Instant at);

    void recordPublishFailure(long seq);

    long unpublishedCount();

    Optional<Instant> oldestUnpublishedAt();

    /** Published-or-not change feed for one tenant, by sequence. */
    List<OutboxEvent> changesAfter(String tenantId, long afterSeq, int limit);
}
