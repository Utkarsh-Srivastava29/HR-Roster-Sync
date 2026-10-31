package com.moveinsync.roster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param store       "jdbc" (Postgres) or "memory"
 * @param dataDir     where uploaded exports and diff spill files live
 * @param brake       safety-brake settings for nightly reconciliation
 * @param relayPeriodMs how often the outbox relay polls
 * @param demoSeed    create office/shift codes and a demo route for the demo tenants at start-up
 */
@ConfigurationProperties(prefix = "roster")
public record RosterProperties(String store, String dataDir, Brake brake, long relayPeriodMs, boolean demoSeed) {

    /** @param maxMissingRatio trip when this share of active employees is missing (0.30 = 30%) */
    public record Brake(double maxMissingRatio, boolean requireTrailer, long minActiveForRatio) {}
}
