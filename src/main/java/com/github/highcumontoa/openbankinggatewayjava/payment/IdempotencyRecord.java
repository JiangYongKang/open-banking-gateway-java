package com.github.highcumontoa.openbankinggatewayjava.payment;

import java.time.Instant;

/**
 * 幂等记录：幂等键 + 合作方 + 报文指纹 -> 已确定的终态结果。
 * 重复提交（同键同报文）只回放结果；同键不同报文为冲突。
 */
public class IdempotencyRecord {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private final String idempotencyKey;
    private final String partnerId;
    private final String payloadHash;
    private final String paymentId;
    private final String status;
    private final String errorCode;
    private final Instant createdAt;

    public IdempotencyRecord(String idempotencyKey, String partnerId, String payloadHash,
                             String paymentId, String status, String errorCode, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.partnerId = partnerId;
        this.payloadHash = payloadHash;
        this.paymentId = paymentId;
        this.status = status;
        this.errorCode = errorCode;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
