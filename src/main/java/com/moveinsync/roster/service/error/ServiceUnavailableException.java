package com.moveinsync.roster.service.error;

/** A dependency (database, disk) is failing; nothing was changed. */
public class ServiceUnavailableException extends ApiException {
    public ServiceUnavailableException(String safeMessage) {
        super("UNAVAILABLE", 503, safeMessage);
    }

    public ServiceUnavailableException(String safeMessage, Throwable cause) {
        super("UNAVAILABLE", 503, safeMessage, cause);
    }
}
