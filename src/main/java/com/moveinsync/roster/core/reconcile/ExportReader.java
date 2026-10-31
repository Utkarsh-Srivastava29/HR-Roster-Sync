package com.moveinsync.roster.core.reconcile;

import com.moveinsync.roster.core.validation.RawHrRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams the nightly export one line at a time (never the whole file in memory).
 *
 * <pre>
 * #EXPORT taken_at=2026-10-30T23:00:00Z            optional header: when the snapshot was taken
 * employee_id,version,source_time,name,email,phone,manager_id,office_code,shift_code
 * E00001,7,2026-10-29T10:00:00Z,Asha Rao,asha@acme.in,+919800000001,E00090,BLR1,GEN
 * ...
 * #END rows=200000                                    trailer: absent or wrong count = truncated file
 * </pre>
 * Standard CSV quoting ("a, b" and "" for a quote) is supported within one line. Extra columns
 * such as pickup_point are read only so they can be reported as ignored.
 */
public final class ExportReader {

    public static final List<String> REQUIRED = List.of(
            "employee_id", "version", "source_time", "name", "email", "phone", "manager_id", "office_code", "shift_code");

    @FunctionalInterface
    public interface RowSink { void row(long lineNo, RawHrRecord record); }

    /** What we learned from the framing of the file (not the rows). */
    public record Framing(Instant takenAt, Long trailerRows, long rowsRead, boolean headerOk, List<String> missingColumns) {}

    public Framing read(BufferedReader in, RowSink sink) throws IOException {
        Instant takenAt = null;
        Long trailer = null;
        int[] idx = null;
        List<String> missing = new ArrayList<>();
        long rows = 0;
        long lineNo = 0;
        String line;
        while ((line = in.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) continue;
            if (line.startsWith("#EXPORT")) { takenAt = parseTakenAt(line); continue; }
            if (line.startsWith("#END")) { trailer = parseRows(line); continue; }
            if (line.startsWith("#")) continue;
            if (idx == null) {
                List<String> header = split(line);
                idx = new int[REQUIRED.size() + 2];
                for (int i = 0; i < REQUIRED.size(); i++) {
                    idx[i] = header.indexOf(REQUIRED.get(i));
                    if (idx[i] < 0) missing.add(REQUIRED.get(i));
                }
                idx[REQUIRED.size()] = header.indexOf("pickup_point");
                idx[REQUIRED.size() + 1] = header.indexOf("special_assistance");
                if (!missing.isEmpty()) return new Framing(takenAt, trailer, 0, false, missing);
                continue;
            }
            if (trailer != null) trailer = -1L;          // data after the trailer: framing is broken
            List<String> f = split(line);
            rows++;
            sink.row(lineNo, new RawHrRecord("UPDATE", at(f, idx[0]), at(f, idx[1]), at(f, idx[2]), at(f, idx[3]),
                    at(f, idx[4]), at(f, idx[5]), at(f, idx[6]), at(f, idx[7]), at(f, idx[8]),
                    at(f, idx[9]), at(f, idx[10])));
        }
        return new Framing(takenAt, trailer, rows, idx != null, missing);
    }

    private static String at(List<String> f, int i) {
        return i >= 0 && i < f.size() ? f.get(i) : null;
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static Instant parseTakenAt(String line) {
        int i = line.indexOf("taken_at=");
        if (i < 0) return null;
        String v = line.substring(i + 9).trim().split("\\s+")[0];
        try { return Instant.parse(v); } catch (DateTimeParseException e) { return null; }
    }

    private static Long parseRows(String line) {
        int i = line.indexOf("rows=");
        if (i < 0) return -1L;
        try { return Long.parseLong(line.substring(i + 5).trim().split("\\s+")[0]); } catch (NumberFormatException e) { return -1L; }
    }
}
