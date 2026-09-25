package com.github.highcumontoa.openbankinggatewayjava.error;

/**
 * 网关统一错误码：对外错误语义稳定且可区分，HTTP 状态码与错误码一一绑定。
 */
public enum ErrorCode {

    UNAUTHENTICATED(401, "UNAUTHENTICATED"),
    TOKEN_EXPIRED(401, "TOKEN_EXPIRED"),
    INSUFFICIENT_SCOPE(403, "INSUFFICIENT_SCOPE"),
    RESOURCE_NOT_FOUND(404, "RESOURCE_NOT_FOUND"),
    VALIDATION_ERROR(400, "VALIDATION_ERROR"),
    INVALID_CONSENT_TRANSITION(409, "INVALID_CONSENT_TRANSITION"),
    CONSENT_EXPIRED(422, "CONSENT_EXPIRED"),
    CONSENT_REVOKED(422, "CONSENT_REVOKED"),
    CONSENT_SUSPENDED(422, "CONSENT_SUSPENDED"),
    CONSENT_NOT_AUTHORIZED(422, "CONSENT_NOT_AUTHORIZED"),
    CONSENT_SCOPE_MISMATCH(403, "CONSENT_SCOPE_MISMATCH"),
    IDEMPOTENCY_CONFLICT(409, "IDEMPOTENCY_CONFLICT"),
    RATE_LIMITED(429, "RATE_LIMITED"),
    INSUFFICIENT_FUNDS(422, "INSUFFICIENT_FUNDS"),
    INTERNAL_ERROR(500, "INTERNAL_ERROR");

    private final int httpStatus;
    private final String code;

    ErrorCode(int httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
