package com.moveinsync.roster.service;

import com.moveinsync.roster.service.cache.TtlLruCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtlLruCacheTest {

    @Test
    void evictsLeastRecentlyUsed() {
        TtlLruCache<String, Integer> c = new TtlLruCache<>(2, 60_000);
        c.put("a", 1);
        c.put("b", 2);
        c.get("a");                 // a is now most recent
        c.put("c", 3);              // evicts b
        assertTrue(c.get("a").isPresent());
        assertFalse(c.get("b").isPresent());
        assertEquals(1, c.evictions());
    }

    @Test
    void entriesExpireAfterTtlWhateverHappens() {
        AtomicLong now = new AtomicLong(0);
        TtlLruCache<String, Integer> c = new TtlLruCache<>(10, 5_000, now::get);
        c.put("a", 1);
        now.set(4_999);
        assertTrue(c.get("a").isPresent());
        now.set(5_000);
        assertFalse(c.get("a").isPresent(), "staleness is bounded by the TTL");
    }

    @Test
    void invalidationByPrefix() {
        TtlLruCache<String, Integer> c = new TtlLruCache<>(10, 60_000);
        c.put("acme|1", 1);
        c.put("globex|1", 2);
        c.invalidateIf(k -> k.startsWith("acme|"));
        assertFalse(c.get("acme|1").isPresent());
        assertTrue(c.get("globex|1").isPresent());
        assertEquals(1, c.hits());
        assertEquals(1, c.misses());
    }
}
