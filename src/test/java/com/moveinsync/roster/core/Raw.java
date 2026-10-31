package com.moveinsync.roster.core;

import com.moveinsync.roster.core.validation.RawHrRecord;
import com.moveinsync.roster.core.validation.TenantCatalog;

import java.util.Set;

/** Small builders for tests. */
public final class Raw {

    public static final TenantCatalog CATALOG = new TenantCatalog(Set.of("BLR1", "BLR2", "HYD1"), Set.of("GEN", "EVE", "NIGHT"));

    private Raw() {}

    public static RawHrRecord upsert(String type, String id, long v, String office, String shift) {
        return new RawHrRecord(type, id, Long.toString(v), "2026-10-30T10:00:00Z", "Asha Rao", "Asha.Rao@Acme.example",
                "+91 98000 00001", null, office, shift, null, null);
    }

    public static RawHrRecord create(String id, long v) { return upsert("CREATE", id, v, "BLR1", "GEN"); }

    public static RawHrRecord update(String id, long v, String office, String shift) { return upsert("UPDATE", id, v, office, shift); }

    public static RawHrRecord deactivate(String id, long v) {
        return new RawHrRecord("DEACTIVATE", id, Long.toString(v), "2026-10-30T10:00:00Z", null, null, null, null, null, null, null, null);
    }

    public static RawHrRecord withPhone(RawHrRecord r, String phone) {
        return new RawHrRecord(r.type(), r.employeeId(), r.version(), r.sourceTime(), r.name(), r.email(), phone, r.managerId(),
                r.officeCode(), r.shiftCode(), r.pickupPoint(), r.specialAssistance());
    }

    public static RawHrRecord withPickup(RawHrRecord r, String pickup) {
        return new RawHrRecord(r.type(), r.employeeId(), r.version(), r.sourceTime(), r.name(), r.email(), r.phone(), r.managerId(),
                r.officeCode(), r.shiftCode(), pickup, null);
    }
}
