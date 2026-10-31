package com.moveinsync.roster.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo user store. Passwords from configuration are BCrypt-hashed at start-up and the plain
 * text is not kept. Lookups for unknown users still run one BCrypt check, so response time does
 * not reveal which usernames exist.
 */
@Component
public class UserDirectory {

    public record StoredUser(String username, String passwordHash, Role role, String tenant, String employeeRef) {}

    private final Map<String, StoredUser> users = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public UserDirectory(SecurityProperties props, PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode("not-a-real-password");
        if (props.users() != null) {
            for (SecurityProperties.User u : props.users()) {
                users.put(u.username(), new StoredUser(u.username(), encoder.encode(u.password()), u.role(), u.tenant(), u.employeeRef()));
            }
        }
    }

    public Optional<StoredUser> authenticate(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        StoredUser u = users.get(username);
        boolean ok = encoder.matches(password, u == null ? dummyHash : u.passwordHash());
        return ok && u != null ? Optional.of(u) : Optional.empty();
    }
}
