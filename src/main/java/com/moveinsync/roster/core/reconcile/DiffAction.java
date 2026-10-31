package com.moveinsync.roster.core.reconcile;

import com.moveinsync.roster.core.domain.EmployeeField;
import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.HrProfile;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * One change the diff wants to make. Written to a spill file one per line so a 200,000-row
 * plan never has to sit in memory.
 */
public sealed interface DiffAction permits DiffAction.Upsert, DiffAction.Deactivate {

    String employeeId();

    /** New or changed employee: offered to the normal version rules (it can still be stale). */
    record Upsert(HrEvent event) implements DiffAction {
        public String employeeId() { return event.employeeId(); }
    }

    /** Active in our store but absent from the file. */
    record Deactivate(String employeeId, long versionSeen) implements DiffAction {}

    // ---- line format (values are validated: no tabs or newlines can occur) ----

    static String encode(DiffAction a) {
        return switch (a) {
            case Upsert u -> {
                HrEvent e = u.event();
                HrProfile p = e.profile();
                yield String.join("\t", "U", e.employeeId(), Long.toString(e.version()), Long.toString(e.sourceTime().toEpochMilli()),
                        p.name(), p.email(), nz(p.phone()), nz(p.managerId()), p.officeCode(), p.shiftCode(),
                        String.join(",", e.ignoredFields().stream().map(Enum::name).sorted().toList()));
            }
            case Deactivate d -> "D\t" + d.employeeId() + "\t" + d.versionSeen();
        };
    }

    static DiffAction decode(String tenantId, String line) {
        String[] f = line.split("\t", -1);
        if (f[0].equals("D")) return new Deactivate(f[1], Long.parseLong(f[2]));
        Set<EmployeeField> ignored = EnumSet.noneOf(EmployeeField.class);
        if (!f[10].isEmpty()) for (String s : f[10].split(",")) ignored.add(EmployeeField.valueOf(s));
        HrProfile p = new HrProfile(f[4], f[5], empty(f[6]), empty(f[7]), f[8], f[9]);
        return new Upsert(new HrEvent(tenantId, HrEvent.Type.UPDATE, f[1], Long.parseLong(f[2]),
                Instant.ofEpochMilli(Long.parseLong(f[3])), p, ignored));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String empty(String s) { return s.isEmpty() ? null : s; }
}
