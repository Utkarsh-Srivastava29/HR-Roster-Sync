package com.moveinsync.roster.service.error;

/** Input failed validation. */
public class InvalidRequestException extends ApiException {
    public InvalidRequestException(String safeMessage) {
        super("INVALID_REQUEST", 400, safeMessage);
    }

    public InvalidRequestException(String safeMessage, Throwable cause) {
        super("INVALID_REQUEST", 400, safeMessage, cause);
    }
}
