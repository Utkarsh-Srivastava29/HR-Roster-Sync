package com.moveinsync.roster.core.validation;

import java.util.Set;

/** A tenant's reference data used to validate records: known office codes and shift codes. */
public record TenantCatalog(Set<String> officeCodes, Set<String> shiftCodes) {
    public TenantCatalog {
        officeCodes = Set.copyOf(officeCodes);
        shiftCodes = Set.copyOf(shiftCodes);
    }
}
