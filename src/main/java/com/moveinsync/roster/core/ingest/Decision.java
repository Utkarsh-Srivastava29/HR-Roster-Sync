package com.moveinsync.roster.core.ingest;

import com.moveinsync.roster.core.domain.ChangeType;
import com.moveinsync.roster.core.domain.Employee;
import com.moveinsync.roster.core.domain.EmployeeField;

import java.util.Set;

/**
 * What {@link ChangePolicy} decided. {@code next} is the record to store (null if nothing is
 * written); {@code changeType} non-null means a change event must be published.
 */
public record Decision(Outcome outcome, Employee next, Set<EmployeeField> changed, ChangeType changeType, String reason) {

    public Decision {
        changed = changed == null ? Set.of() : Set.copyOf(changed);
    }

    public boolean writes() { return next != null; }

    public boolean emitsEvent() { return changeType != null && !changed.isEmpty(); }

    static Decision noWrite(Outcome o, String reason) { return new Decision(o, null, Set.of(), null, reason); }
}
