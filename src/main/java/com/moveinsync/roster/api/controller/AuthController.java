package com.moveinsync.roster.api.controller;

import com.moveinsync.roster.api.dto.LoginRequest;
import com.moveinsync.roster.api.dto.TokenResponse;
import com.moveinsync.roster.security.TokenService;
import com.moveinsync.roster.security.UserDirectory;
import com.moveinsync.roster.service.error.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserDirectory users;
    private final TokenService tokens;

    public AuthController(UserDirectory users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Same answer for "no such user" and "wrong password". */
    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody LoginRequest req) {
        var u = users.authenticate(req.username(), req.password())
                .orElseThrow(() -> new UnauthorizedException("invalid username or password"));
        var issued = tokens.issue(u);
        return new TokenResponse(issued.token(), "Bearer", issued.expiresInSeconds(), u.role().name(), u.tenant());
    }
}
