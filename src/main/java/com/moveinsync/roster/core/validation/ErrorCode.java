package com.moveinsync.roster.core.validation;

/** Machine-readable reasons a record is rejected. Messages never contain the offending value. */
public enum ErrorCode {
    MISSING,
    MALFORMED,
    TOO_LONG,
    UNKNOWN_OFFICE,
    UNKNOWN_SHIFT,
    BAD_VERSION,
    BAD_TIMESTAMP,
    BAD_EVENT_TYPE,
    SELF_MANAGER,
    /** Same version as already applied but different content. */
    VERSION_REUSED
}
