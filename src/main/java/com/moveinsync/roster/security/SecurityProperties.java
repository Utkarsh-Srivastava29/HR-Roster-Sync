package com.moveinsync.roster.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param jwtSecret HMAC key, at least 32 bytes; from the JWT_SECRET environment variable in production
 * @param tokenTtl  how long an access token lives
 * @param users     demo users (a real deployment would use the client's identity provider)
 */
@ConfigurationProperties(prefix = "roster.security")
public record SecurityProperties(String jwtSecret, Duration tokenTtl, List<User> users) {

    /** @param employeeRef only for EMPLOYEE: their own employee id */
    public record User(String username, String password, Role role, String tenant, String employeeRef) {}
}
