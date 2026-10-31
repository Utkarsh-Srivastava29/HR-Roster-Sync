package com.moveinsync.roster.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One employee of one tenant. Immutable; every change produces a new instance.
 *
 * @param hrVersion        highest HR version applied (from the source system)
 * @param hrSourceTime     source timestamp of that HR version
 * @param hrHash           {@link HrProfile#contentHash()} of {@code hr}
 * @param transportVersion bumped by every transport-admin edit (optimistic concurrency)
 */
public record Employee(String tenantId,
                       String employeeId,
                       EmployeeStatus status,
                       HrProfile hr,
                       long hrVersion,
                       Instant hrSourceTime,
                       long hrHash,
                       TransportProfile transport,
                       long transportVersion,
                       Instant updatedAt) {

    public Employee {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(employeeId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(hr);
        transport = transport == null ? TransportProfile.EMPTY : transport;
    }

    public boolean active() { return status == EmployeeStatus.ACTIVE; }

    public Employee withHr(HrProfile p, long version, Instant sourceTime, EmployeeStatus st, Instant now) {
        return new Employee(tenantId, employeeId, st, p, version, sourceTime, p.contentHash(), transport, transportVersion, now);
    }

    public Employee withStatus(EmployeeStatus st, long version, Instant sourceTime, Instant now) {
        return new Employee(tenantId, employeeId, st, hr, version, sourceTime, hrHash, transport, transportVersion, now);
    }

    public Employee withTransport(TransportProfile t, Instant now) {
        return new Employee(tenantId, employeeId, status, hr, hrVersion, hrSourceTime, hrHash, t, transportVersion + 1, now);
    }

    /** Ids only. */
    @Override
    public String toString() {
        return "Employee[" + tenantId + "/" + employeeId + ", " + status + ", hrV" + hrVersion + ", tV" + transportVersion + "]";
    }
}
