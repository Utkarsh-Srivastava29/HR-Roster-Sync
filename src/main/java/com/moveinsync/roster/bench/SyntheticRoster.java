package com.moveinsync.roster.bench;

import com.moveinsync.roster.core.domain.HrEvent;
import com.moveinsync.roster.core.domain.HrProfile;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Deterministic fake employees and export files for tests and the benchmark. */
public final class SyntheticRoster {

    public static final Set<String> OFFICES = Set.of("BLR1", "BLR2", "HYD1", "PUN1", "CHN1");
    public static final Set<String> SHIFTS = Set.of("GEN", "EVE", "NIGHT");
    private static final String[] OFFICE_LIST = {"BLR1", "BLR2", "HYD1", "PUN1", "CHN1"};
    private static final String[] SHIFT_LIST = {"GEN", "EVE", "NIGHT"};
    private static final String[] FIRST = {"Asha", "Ravi", "Meera", "Arjun", "Divya", "Kiran", "Neha", "Vikram", "Priya", "Rahul"};
    private static final String[] LAST = {"Rao", "Sharma", "Iyer", "Patel", "Nair", "Singh", "Gupta", "Reddy", "Das", "Menon"};

    private SyntheticRoster() {}

    public static String id(int i) { return String.format("E%07d", i); }

    public static HrProfile profile(int i, int variant) {
        Random r = new Random(i * 31L + variant);
        String name = FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
        String email = "emp" + i + (variant == 0 ? "" : "." + variant) + "@acme.example";
        String phone = "+9198" + String.format("%08d", Math.floorMod(i * 7919L + variant, 100_000_000L));
        String manager = i > 10 ? id(i / 10) : null;
        return new HrProfile(name, email, phone, manager, OFFICE_LIST[r.nextInt(OFFICE_LIST.length)], SHIFT_LIST[r.nextInt(SHIFT_LIST.length)]);
    }

    public static HrEvent create(String tenant, int i, long version, Instant at) {
        return new HrEvent(tenant, HrEvent.Type.CREATE, id(i), version, at, profile(i, 0), Set.of());
    }

    /** One export row. */
    public record Row(String id, long version, Instant sourceTime, HrProfile p) {}

    /**
     * Writes an export. {@code limit} rows are written (fewer than rows.size() = a cut-off file);
     * the trailer is written only if {@code trailer} is true and states rows.size().
     */
    public static void writeExport(Path file, List<Row> rows, Instant takenAt, int limit, boolean trailer) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("#EXPORT taken_at=" + takenAt + "\n");
            w.write("employee_id,version,source_time,name,email,phone,manager_id,office_code,shift_code\n");
            int n = 0;
            for (Row r : rows) {
                if (n++ >= limit) break;
                HrProfile p = r.p();
                w.write(String.join(",", r.id(), Long.toString(r.version()), r.sourceTime().toString(), csv(p.name()), p.email(),
                        p.phone() == null ? "" : p.phone(), p.managerId() == null ? "" : p.managerId(), p.officeCode(), p.shiftCode()));
                w.write('\n');
            }
            if (trailer) w.write("#END rows=" + rows.size() + "\n");
        }
    }

    public static List<Row> rows(int from, int to, long version, Instant at, int variant) {
        List<Row> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) out.add(new Row(id(i), version, at, profile(i, variant)));
        return out;
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
