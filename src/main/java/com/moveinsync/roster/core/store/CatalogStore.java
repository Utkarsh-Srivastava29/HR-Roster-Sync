package com.moveinsync.roster.core.store;

import com.moveinsync.roster.core.validation.TenantCatalog;

import java.util.Optional;

public interface CatalogStore {
    Optional<TenantCatalog> catalog(String tenantId);
    void putCatalog(String tenantId, TenantCatalog catalog);
}
