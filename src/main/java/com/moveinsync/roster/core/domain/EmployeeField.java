package com.moveinsync.roster.core.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every field of an employee record and its single owner.
 *
 * <p><b>Conflict rule.</b> A change from one owner may only set that owner's fields. Fields of
 * the other owner that appear in its payload are ignored (and reported back as ignored, not as
 * an error). Each owner has its own version stream, so an HR update can never overwrite a
 * transport-owned field, and a transport edit can never make an HR update look stale.
 */
public enum EmployeeField {
    NAME(Owner.HR),
    EMAIL(Owner.HR),
    PHONE(Owner.HR),
    MANAGER_ID(Owner.HR),
    OFFICE_CODE(Owner.HR),
    SHIFT_CODE(Owner.HR),
    /** Active / inactive. Changed by HR (create, deactivate) and by approved reconciliation. */
    STATUS(Owner.HR),
    PICKUP_POINT(Owner.TRANSPORT_ADMIN),
    SPECIAL_ASSISTANCE(Owner.TRANSPORT_ADMIN);

    private final Owner owner;

    EmployeeField(Owner owner) {
        this.owner = owner;
    }

    public Owner owner() { return owner; }

    public static Set<EmployeeField> ownedBy(Owner o) {
        Set<EmployeeField> s = EnumSet.noneOf(EmployeeField.class);
        for (EmployeeField f : values()) if (f.owner == o) s.add(f);
        return s;
    }
}
