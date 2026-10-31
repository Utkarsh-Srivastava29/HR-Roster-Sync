package com.moveinsync.roster.core.ingest;

/** Result of offering one change to the store. */
public enum Outcome {
    /** Fields changed; a change event was written in the same transaction. */
    APPLIED,
    /** Newer version but every field already had that value; version recorded, no event. */
    UNCHANGED,
    /** Same version and same content seen before: no-op. */
    DUPLICATE,
    /** Older than what is stored: ignored. */
    STALE,
    /** Same version with different content, or a transport edit based on an old version: refused. */
    CONFLICT,
    /** Failed validation at the boundary. */
    REJECTED
}
