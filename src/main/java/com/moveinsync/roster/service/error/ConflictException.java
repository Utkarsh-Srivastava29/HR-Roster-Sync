package com.moveinsync.roster.service.error;

/** Request conflicts with the current state, e.g. no plan yet. */
public class ConflictException extends ApiException {
    public ConflictException(String safeMessage) {
        super("CONFLICT", 409, safeMessage);
    }

    public ConflictException(String safeMessage, Throwable cause) {
        super("CONFLICT", 409, safeMessage, cause);
    }
}
