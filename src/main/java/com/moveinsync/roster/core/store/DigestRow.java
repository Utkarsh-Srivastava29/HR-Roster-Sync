package com.moveinsync.roster.core.store;

import java.time.Instant;

/** What the diff needs to know about a stored employee - no personal data. */
public record DigestRow(String employeeId, int bucket, long hrHash, long hrVersion, boolean active, Instant hrSourceTime) {}
