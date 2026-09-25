package com.github.highcumontoa.openbankinggatewayjava.error;

import org.springframework.http.HttpStatus;

/**
 * 可区分的错误类型。对外统一通过 {@link #httpStatus} + code 表达，
 * 资源不存在与跨合作方访问共享同一对外形态（NOT_FOUND），避免泄漏资源归属。
 */
public enum ErrorCode {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "unauthenticated"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "token_expired"),
    INSUFFICIENT_SCOPE(HttpStatus.FORBIDDEN, "insufficient_scope"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "resource_not_found"),
    CONSENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "consent_already_exists"),
    INVALID_CONSENT_STATE(HttpStatus.CONFLICT, "invalid_consent_state"),
    CONSENT_EXPIRED(HttpStatus.CONFLICT, "consent_expired"),
    CONSENT_REVOKED(HttpStatus.CONFLICT, "consent_revoked"),
    CONSENT_SCOPE_MISMATCH(HttpStatus.FORBIDDEN, "consent_scope_mismatch"),
    ACCOUNT_NOT_IN_CONSENT(HttpStatus.FORBIDDEN, "account_not_in_consent"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "idempotency_conflict"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate_limited"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation_error"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient_funds"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "resource_not_found"),
    LEDGER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "ledger_error");

    private final HttpStatus httpStatus;
    private final String code;

    ErrorCode(HttpStatus httpStatus, String code) {
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
