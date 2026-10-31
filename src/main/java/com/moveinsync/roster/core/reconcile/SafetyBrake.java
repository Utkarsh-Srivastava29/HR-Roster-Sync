package com.moveinsync.roster.core.reconcile;

import java.util.ArrayList;
import java.util.List;

/**
 * Stops a nightly run that looks like a broken export. If tripped, the run applies nothing -
 * no deactivations and no updates - until an operator approves it.
 *
 * @param maxMissingRatio  trip when this fraction of active employees is missing (default 0.30)
 * @param requireTrailer   trip when the "#END rows=N" trailer is absent or does not match
 * @param minActiveForRatio below this many active employees the ratio is not used (tiny tenants)
 */
public record SafetyBrake(double maxMissingRatio, boolean requireTrailer, long minActiveForRatio) {

    public SafetyBrake {
        if (maxMissingRatio <= 0 || maxMissingRatio > 1) throw new IllegalArgumentException("maxMissingRatio in (0,1]");
    }

    public static SafetyBrake defaults() {
        return new SafetyBrake(0.30, true, 20);
    }

    public enum Reason { TOO_MANY_MISSING, TRUNCATED_FILE, BAD_HEADER, NO_VALID_ROWS }

    /** @param appearsMissing how many records the file seems to be missing, shown to the operator */
    public record Decision(boolean tripped, List<Reason> reasons, long appearsMissing, double missingRatio, String message) {}

    public Decision evaluate(DiffStats s, boolean headerOk) {
        List<Reason> r = new ArrayList<>();
        if (!headerOk) r.add(Reason.BAD_HEADER);
        boolean trailerBad = s.trailerRows() == null || s.trailerRows() < 0 || s.trailerRows() != s.rowsRead();
        if (requireTrailer && trailerBad) r.add(Reason.TRUNCATED_FILE);
        if (s.activeInStore() >= minActiveForRatio && s.missingRatio() >= maxMissingRatio) r.add(Reason.TOO_MANY_MISSING);
        if (s.validRows() == 0 && s.activeInStore() > 0) r.add(Reason.NO_VALID_ROWS);

        long byTrailer = s.trailerRows() != null && s.trailerRows() > s.rowsRead() ? s.trailerRows() - s.rowsRead() : 0;
        long appears = Math.max(s.missing(), byTrailer);
        String msg = r.isEmpty()
                ? String.format("ok: %d missing (%.1f%% of %d active), below the %.0f%% threshold",
                        s.missing(), s.missingRatio() * 100, s.activeInStore(), maxMissingRatio * 100)
                : String.format("BRAKE: the file appears to be missing %,d records (%.1f%% of %,d active employees; threshold %.0f%%)%s. Nothing was applied; approve or reject this run.",
                        appears, s.missingRatio() * 100, s.activeInStore(), maxMissingRatio * 100,
                        r.contains(Reason.TRUNCATED_FILE) ? "; trailer missing or row count mismatch" : "");
        return new Decision(!r.isEmpty(), r, appears, s.missingRatio(), msg);
    }
}
