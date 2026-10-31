package com.moveinsync.roster.core.store;

import java.nio.charset.StandardCharsets;

/**
 * Hash buckets for the nightly diff. An employee always lands in the same bucket, and each
 * bucket has an order-independent digest: XOR of a 64-bit mix of (employeeId, content hash) over
 * its active employees. Two buckets with equal digests are (with overwhelming probability)
 * identical, so the diff only opens buckets whose digests differ.
 */
public final class Buckets {

    public static final int COUNT = 4096;

    private Buckets() {}

    public static int of(String employeeId) {
        return (int) Long.remainderUnsigned(fnv1a(employeeId), COUNT);
    }

    /** Contribution of one active employee to its bucket digest. */
    public static long digest(String employeeId, long contentHash) {
        return mix(fnv1a(employeeId) ^ (contentHash * 0x9E3779B97F4A7C15L));
    }

    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** SplitMix64 finaliser: spreads bits so XOR of digests is a good set fingerprint. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
