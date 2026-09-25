package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.time.Instant;

/**
 * 幂等记录：键为 (partnerId, idempotencyKey)。
 * 保存首次请求报文指纹与首次支付结果；同键同报文直接回放结果，同键异报文判冲突。
 */
public class IdempotencyRecord {

    private final String partnerId;
    private final String idempotencyKey;
    private final String requestFingerprint;
    private final Payment payment;
    private final Instant createdAt;

    public IdempotencyRecord(String partnerId, String idempotencyKey, String requestFingerprint, Payment payment) {
        this.partnerId = partnerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.payment = payment;
        this.createdAt = Instant.now();
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

    public Payment payment() {
        return payment;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
