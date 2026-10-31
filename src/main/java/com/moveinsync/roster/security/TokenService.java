package com.moveinsync.roster.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Issues short-lived HS256 tokens carrying subject, tenant, role and (for employees) employee ref. */
@Component
public class TokenService {

    public record Issued(String token, long expiresInSeconds) {}

    private final JwtEncoder encoder;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder, SecurityProperties props) {
        this.encoder = encoder;
        this.ttl = props.tokenTtl() == null ? Duration.ofHours(8) : props.tokenTtl();
    }

    public Issued issue(UserDirectory.StoredUser u) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("hr-roster-sync")
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(u.username())
                .claim("tenant", u.tenant())
                .claim("roles", List.of(u.role().name()));
        if (u.employeeRef() != null) claims.claim("emp", u.employeeRef());
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new Issued(token, ttl.toSeconds());
    }
}
