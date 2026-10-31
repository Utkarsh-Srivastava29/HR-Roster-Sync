package com.moveinsync.roster.service.error;

/**
 * Base of every error a client can see. The message must be safe to show: no personal data,
 * no ids of other tenants, no stack details. Subclasses fix the HTTP status and error code.
 */
public abstract class ApiException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    protected ApiException(String code, int httpStatus, String safeMessage) {
        super(safeMessage);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    protected ApiException(String code, int httpStatus, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
}
