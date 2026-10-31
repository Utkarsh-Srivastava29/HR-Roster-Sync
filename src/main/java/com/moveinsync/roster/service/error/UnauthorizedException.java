package com.moveinsync.roster.service.error;

/** Bad credentials. Same message whether the user exists or not. */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException(String safeMessage) {
        super("UNAUTHORIZED", 401, safeMessage);
    }
}
