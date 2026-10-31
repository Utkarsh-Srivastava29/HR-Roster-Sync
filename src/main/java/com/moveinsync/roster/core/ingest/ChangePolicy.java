package com.moveinsync.roster.core.ingest;

import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.EmployeeStatus;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.HrProfile;
import com.moveinsync.roster.core.domain.TransportEdit;
import com.moveinsync.roster.core.domain.TransportProfile;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The rules, as pure functions of (current record, incoming change). No I/O, no locks; the
 * store calls this inside a per-employee transaction, so the decision and the write are atomic.
 *
 * <h2>HR changes (per-employee version order)</h2>
 * <ul>
 *   <li>version &lt; stored → STALE, ignored</li>
 *   <li>version = stored, same content → DUPLICATE, no-op</li>
 *   <li>version = stored, different content → CONFLICT (the source reused a version)</li>
 *   <li>version &gt; stored → apply HR-owned fields only; transport fields are untouched</li>
 * </ul>
 * A DEACTIVATE for an unknown employee is stored as a tombstone so a late CREATE with a lower
 * version is recognised as stale.
 *
 * <h2>Transport edits</h2> Only pickup point and special assistance; optimistic concurrency on
 * the transport version. They never touch HR fields or the HR version.
 */
public final class ChangePolicy {

    private static final Set<EmployeeField> ALL_HR = EmployeeField.ownedBy(com.moveinsync.roster.core.domain.Owner.HR);

    public Decision decideHr(Optional<Employee> current, HrEvent ev, Instant now) {
        if (current.isEmpty()) {
            if (ev.type() == HrEvent.Type.DEACTIVATE) {
                Employee tomb = new Employee(ev.tenantId(), ev.employeeId(), EmployeeStatus.INACTIVE, HrProfile.UNKNOWN,
                        ev.version(), ev.sourceTime(), HrProfile.UNKNOWN.contentHash(), TransportProfile.EMPTY, 0, now);
                return new Decision(Outcome.APPLIED, tomb, Set.of(), null, "deactivated before it was created; tombstone kept");
            }
            Employee created = new Employee(ev.tenantId(), ev.employeeId(), EmployeeStatus.ACTIVE, ev.profile(),
                    ev.version(), ev.sourceTime(), ev.profile().contentHash(), TransportProfile.EMPTY, 0, now);
            return new Decision(Outcome.APPLIED, created, ALL_HR, ChangeType.CREATED, null);
        }

        Employee cur = current.get();
        if (ev.version() < cur.hrVersion())
            return Decision.noWrite(Outcome.STALE, "version " + ev.version() + " is older than stored " + cur.hrVersion());
        if (ev.version() == cur.hrVersion())
            return ev.sameChangeAs(cur) ? Decision.noWrite(Outcome.DUPLICATE, "already applied")
                    : Decision.noWrite(Outcome.CONFLICT, "version " + ev.version() + " already applied with different content");

        if (ev.type() == HrEvent.Type.DEACTIVATE) {
            Employee next = cur.withStatus(EmployeeStatus.INACTIVE, ev.version(), ev.sourceTime(), now);
            return cur.active()
                    ? new Decision(Outcome.APPLIED, next, Set.of(EmployeeField.STATUS), ChangeType.DEACTIVATED, null)
                    : new Decision(Outcome.UNCHANGED, next, Set.of(), null, "already inactive");
        }

        boolean wasTomb = cur.hr().unknown();
        Set<EmployeeField> changed = EnumSet.noneOf(EmployeeField.class);
        changed.addAll(wasTomb ? ALL_HR : cur.hr().diff(ev.profile()));
        if (!cur.active()) changed.add(EmployeeField.STATUS);
        Employee next = cur.withHr(ev.profile(), ev.version(), ev.sourceTime(), EmployeeStatus.ACTIVE, now);
        if (changed.isEmpty()) return new Decision(Outcome.UNCHANGED, next, Set.of(), null, "no field changed");
        ChangeType type = wasTomb ? ChangeType.CREATED : (!cur.active() ? ChangeType.REACTIVATED : ChangeType.UPDATED);
        return new Decision(Outcome.APPLIED, next, changed, type, null);
    }

    /**
     * Nightly reconciliation found an active employee missing from the export. Deactivate only
     * if nothing changed since the diff looked at it: same HR version, and no source change after
     * the export was taken. Keeps the HR version, so any later real HR event still applies.
     */
    public Decision decideReconciliationDeactivate(Optional<Employee> current, long versionSeenInDiff, Instant exportTakenAt, Instant now) {
        if (current.isEmpty()) return Decision.noWrite(Outcome.STALE, "no longer exists");
        Employee cur = current.get();
        if (!cur.active()) return Decision.noWrite(Outcome.DUPLICATE, "already inactive");
        if (cur.hrVersion() != versionSeenInDiff || cur.hrSourceTime().isAfter(exportTakenAt))
            return Decision.noWrite(Outcome.STALE, "changed after the export was taken");
        Employee next = cur.withStatus(EmployeeStatus.INACTIVE, cur.hrVersion(), cur.hrSourceTime(), now);
        return new Decision(Outcome.APPLIED, next, Set.of(EmployeeField.STATUS), ChangeType.DEACTIVATED, null);
    }

    public Decision decideTransport(Employee cur, TransportEdit edit, Instant now) {
        if (edit.expectedVersion() != cur.transportVersion())
            return Decision.noWrite(Outcome.CONFLICT, "transport fields were edited by someone else; reload and retry");
        TransportProfile next = new TransportProfile(blankToNull(edit.pickupPoint()), edit.specialAssistance());
        Set<EmployeeField> changed = EnumSet.noneOf(EmployeeField.class);
        if (!Objects.equals(next.pickupPoint(), cur.transport().pickupPoint())) changed.add(EmployeeField.PICKUP_POINT);
        if (!next.specialAssistance().equals(cur.transport().specialAssistance())) changed.add(EmployeeField.SPECIAL_ASSISTANCE);
        if (changed.isEmpty()) return Decision.noWrite(Outcome.UNCHANGED, "no field changed");
        return new Decision(Outcome.APPLIED, cur.withTransport(next, now), changed, ChangeType.TRANSPORT_UPDATED, null);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
