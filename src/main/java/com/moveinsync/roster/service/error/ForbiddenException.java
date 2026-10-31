package com.moveinsync.roster.service.error;

/** Authenticated but not allowed. */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String safeMessage) {
        super("FORBIDDEN", 403, safeMessage);
    }

    public ForbiddenException(String safeMessage, Throwable cause) {
        super("FORBIDDEN", 403, safeMessage, cause);
    }
}
