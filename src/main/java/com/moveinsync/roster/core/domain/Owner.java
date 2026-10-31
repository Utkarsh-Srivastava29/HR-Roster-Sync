package com.moveinsync.roster.core.domain;

/** Who is allowed to change a field. */
public enum Owner {
    /** The client's HR system (via the API feed or the nightly export). */
    HR,
    /** MoveInSync's transport admin (via our own API). */
    TRANSPORT_ADMIN
}
