package com.moveinsync.roster.core.domain;

import java.util.List;

/**
 * A transport admin's edit. {@code expectedVersion} is the transport version the admin last
 * saw (optimistic concurrency): a mismatch means someone else edited first.
 */
public record TransportEdit(String pickupPoint, List<String> specialAssistance, long expectedVersion) {}
