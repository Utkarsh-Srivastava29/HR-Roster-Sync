package com.moveinsync.roster.service.error;

/** Resource not found (ids from another tenant also return this, so existence never leaks). */
public class NotFoundException extends ApiException {
    public NotFoundException(String safeMessage) {
        super("NOT_FOUND", 404, safeMessage);
    }

    public NotFoundException(String safeMessage, Throwable cause) {
        super("NOT_FOUND", 404, safeMessage, cause);
    }
}
