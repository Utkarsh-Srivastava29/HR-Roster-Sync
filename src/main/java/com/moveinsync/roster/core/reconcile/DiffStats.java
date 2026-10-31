package com.moveinsync.roster.core.reconcile;

import java.time.Instant;
import java.util.Map;

/**
 * Counts from the diff. {@code missing} = active employees in our store that the file does not
 * contain (deactivation candidates).
 */
public record DiffStats(long rowsRead,
                        long validRows,
                        long invalidRows,
                        Map<String, Long> errorsByCode,
                        long newEmployees,
                        long changed,
                        long unchanged,
                        long missing,
                        long activeInStore,
                        long duplicateRowsInFile,
                        int bucketsCompared,
                        int bucketsOpened,
                        long maxRowsInMemory,
                        Long trailerRows,
                        Instant takenAt,
                        long diffMillis) {

    public double missingRatio() {
        return activeInStore == 0 ? 0 : (double) missing / activeInStore;
    }
}
