package com.moveinsync.roster.security;

import com.moveinsync.roster.service.error.ForbiddenException;
import org.slf4j.MDC;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The caller, as proven by a signed token. The tenant comes from here and only here; no
 * endpoint accepts a tenant id from the path, query or body. That is what makes it impossible
 * for one client to read another client's data.
 *
 * <p>{@code employeeRef} (EMPLOYEE only) is the caller's own employee id.
 */
public record CurrentUser(String username, String tenant, Role role, String employeeRef) {

    private static final Pattern TENANT = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    public static CurrentUser from(Jwt jwt) {
        String tenant = jwt.getClaimAsString("tenant");
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (tenant == null || !TENANT.matcher(tenant).matches() || roles == null || roles.isEmpty())
            throw new ForbiddenException("token has no valid tenant or role");
        Role role = Role.valueOf(roles.getFirst());
        MDC.put("tenant", tenant);
        MDC.put("role", role.name());
        return new CurrentUser(jwt.getSubject(), tenant, role, jwt.getClaimAsString("emp"));
    }
}
