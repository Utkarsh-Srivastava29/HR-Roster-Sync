package com.moveinsync.roster.service;

import com.moveinsync.roster.core.store.CatalogStore;
import com.moveinsync.roster.core.validation.TenantCatalog;
import com.moveinsync.roster.service.cache.TtlLruCache;
import com.moveinsync.roster.service.error.InvalidRequestException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Office and shift codes per tenant. Read for every record validated, so it is cached.
 * <b>Staleness:</b> a catalog change is visible immediately on the instance that made it
 * (invalidated) and within {@link #TTL_MS} on any other instance.
 */
public class CatalogService {

    public static final long TTL_MS = 60_000;
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_-]{1,16}");
    private static final TenantCatalog EMPTY = new TenantCatalog(Set.of(), Set.of());

    private final CatalogStore store;
    private final TtlLruCache<String, TenantCatalog> cache = new TtlLruCache<>(10_000, TTL_MS);

    public CatalogService(CatalogStore store) {
        this.store = store;
    }

    public TenantCatalog get(String tenantId) {
        return cache.getOrCompute(tenantId, t -> store.catalog(t).orElse(EMPTY));
    }

    public TenantCatalog put(String tenantId, Set<String> offices, Set<String> shifts) {
        TenantCatalog c = new TenantCatalog(normalise(offices), normalise(shifts));
        store.putCatalog(tenantId, c);
        cache.invalidateIf(k -> k.equals(tenantId));
        return c;
    }

    public TtlLruCache<String, TenantCatalog> cache() { return cache; }

    private static Set<String> normalise(Set<String> codes) {
        if (codes == null) throw new InvalidRequestException("codes are required");
        Set<String> out = codes.stream().map(c -> c == null ? "" : c.trim().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        for (String c : out) if (!CODE.matcher(c).matches()) throw new InvalidRequestException("codes must match [A-Z0-9_-]{1,16}");
        return out;
    }
}
