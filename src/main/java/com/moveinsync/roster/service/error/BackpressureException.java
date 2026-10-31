package com.moveinsync.roster.service.error;

/** Event queue is full; client should retry with backoff. */
public class BackpressureException extends ApiException {
    public BackpressureException(String safeMessage) {
        super("TOO_MANY_REQUESTS", 429, safeMessage);
    }

    public BackpressureException(String safeMessage, Throwable cause) {
        super("TOO_MANY_REQUESTS", 429, safeMessage, cause);
    }
}
