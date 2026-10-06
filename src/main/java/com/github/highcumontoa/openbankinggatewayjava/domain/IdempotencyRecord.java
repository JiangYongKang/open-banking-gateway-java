package com.github.highcumontoa.openbankinggatewayjava.domain;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;

import java.time.Instant;

/**
 * 幂等记录：键为 (partnerId, idempotencyKey)。
 * 保存首次请求报文指纹与首次处理结果（成功支付或业务拒绝）。
 * 同键同报文原样回放首次结果（成功返回支付、失败原样返回当时的可区分原因）；
 * 同键异报文判冲突。记录有保留期，超窗可清理，清理不影响已生效支付与账务。
 */
public class IdempotencyRecord {

    public enum Outcome {
        COMPLETED,
        REJECTED
    }

    private final String partnerId;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Outcome outcome;
    private final Payment payment;
    private final ErrorCode rejectReason;
    private final String rejectMessage;
    private final Instant createdAt;

    private IdempotencyRecord(String partnerId, String idempotencyKey, String requestFingerprint,
                              Outcome outcome, Payment payment, ErrorCode rejectReason, String rejectMessage) {
        this.partnerId = partnerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.outcome = outcome;
        this.payment = payment;
        this.rejectReason = rejectReason;
        this.rejectMessage = rejectMessage;
        this.createdAt = Instant.now();
    }

    public static IdempotencyRecord completed(String partnerId, String idempotencyKey,
                                              String requestFingerprint, Payment payment) {
        return new IdempotencyRecord(partnerId, idempotencyKey, requestFingerprint,
                Outcome.COMPLETED, payment, null, null);
    }

    public static IdempotencyRecord rejected(String partnerId, String idempotencyKey,
                                             String requestFingerprint, ErrorCode reason, String message) {
        return new IdempotencyRecord(partnerId, idempotencyKey, requestFingerprint,
                Outcome.REJECTED, null, reason, message);
    }

    public String partnerId() {
        return partnerId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Payment payment() {
        return payment;
    }

    public ErrorCode rejectReason() {
        return rejectReason;
    }

    public String rejectMessage() {
        return rejectMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
