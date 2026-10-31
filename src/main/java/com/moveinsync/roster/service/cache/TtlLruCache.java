package com.moveinsync.roster.service.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Small thread-safe cache: least-recently-used eviction once {@code maxEntries} is reached,
 * and every entry expires {@code ttlMillis} after it was written, whatever happens.
 * The TTL is the hard upper bound on staleness even if an invalidation is missed.
 */
public final class TtlLruCache<K, V> {

    private record Entry<V>(V value, long writtenAt) {}

    private final int maxEntries;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final LinkedHashMap<K, Entry<V>> map;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public TtlLruCache(int maxEntries, long ttlMillis) {
        this(maxEntries, ttlMillis, System::currentTimeMillis);
    }

    public TtlLruCache(int maxEntries, long ttlMillis, LongSupplier clock) {
        if (maxEntries <= 0 || ttlMillis <= 0) throw new IllegalArgumentException("size and ttl must be > 0");
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                boolean evict = size() > TtlLruCache.this.maxEntries;
                if (evict) evictions.incrementAndGet();
                return evict;
            }
        };
    }

    public synchronized Optional<V> get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) { misses.incrementAndGet(); return Optional.empty(); }
        if (clock.getAsLong() - e.writtenAt() >= ttlMillis) {
            map.remove(key);
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(e.value());
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, clock.getAsLong()));
    }

    public V getOrCompute(K key, Function<K, V> loader) {
        Optional<V> v = get(key);
        if (v.isPresent()) return v.get();
        V computed = loader.apply(key);   // computed outside the lock; a duplicate load is harmless
        put(key, computed);
        return computed;
    }

    public synchronized void invalidateIf(Predicate<K> p) {
        Iterator<K> it = map.keySet().iterator();
        while (it.hasNext()) if (p.test(it.next())) it.remove();
    }

    public synchronized void invalidateAll() { map.clear(); }

    public synchronized int size() { return map.size(); }
    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }
    public long evictions() { return evictions.get(); }
}
