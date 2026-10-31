package com.moveinsync.roster.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * A validated change from the HR system.
 *
 * <p><b>Idempotency key</b> = (tenant, employeeId, HR version). <b>Version comparison</b>: the
 * source's per-employee version (a counter, or the source timestamp in epoch millis when the
 * source only has timestamps) is compared with the highest HR version already applied:
 * greater = apply; equal with the same content = duplicate; equal with different content =
 * conflict (rejected, reported); smaller = stale (ignored).
 *
 * @param profile        null for DEACTIVATE
 * @param ignoredFields  transport-owned fields that were present in the payload and ignored
 */
public record HrEvent(String tenantId,
                      Type type,
                      String employeeId,
                      long version,
                      Instant sourceTime,
                      HrProfile profile,
                      Set<EmployeeField> ignoredFields) {

    public enum Type { CREATE, UPDATE, DEACTIVATE }

    public HrEvent {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(employeeId);
        Objects.requireNonNull(sourceTime);
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (type != Type.DEACTIVATE) Objects.requireNonNull(profile, "profile required for " + type);
        ignoredFields = ignoredFields == null ? Set.of() : Set.copyOf(ignoredFields);
    }

    public String idempotencyKey() {
        return tenantId + ":" + employeeId + ":hr:" + version;
    }

    /** Does this event describe exactly the state {@code e} is in? (Same version + same content = duplicate.) */
    public boolean sameChangeAs(Employee e) {
        if (type == Type.DEACTIVATE) return e.status() == EmployeeStatus.INACTIVE;
        return e.status() == EmployeeStatus.ACTIVE && e.hrHash() == profile.contentHash();
    }
}
