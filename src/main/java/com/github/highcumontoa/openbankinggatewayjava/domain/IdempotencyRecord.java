package com.github.highcumontoa.openbankinggatewayjava.domain;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;

import java.time.Instant;

/**
 * 幂等记录：键为 (partnerId, idempotencyKey)。
 * 保存首次请求报文指纹与首次判定结果；同键同报文直接回放结果，同键异报文判冲突。
 *
 * 结果分为两种结局：
 * <ul>
 *   <li>COMPLETED：支付已生效，保存支付事实；</li>
 *   <li>REJECTED：业务拒绝（余额不足/同意书不可用/限流等），保存当时的可区分原因，
 *       回查与重放都原样返回，不重新执行。</li>
 * </ul>
 * 记录有保留窗口（obg.idempotency.retention），超窗可清理；
 * 清理只影响回查，不影响已生效的支付与账务。
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
    private final ErrorCode rejectionCode;
    private final String rejectionMessage;
    private final Instant createdAt;

    private IdempotencyRecord(String partnerId, String idempotencyKey, String requestFingerprint,
                              Outcome outcome, Payment payment,
                              ErrorCode rejectionCode, String rejectionMessage) {
        this.partnerId = partnerId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.outcome = outcome;
        this.payment = payment;
        this.rejectionCode = rejectionCode;
        this.rejectionMessage = rejectionMessage;
        this.createdAt = Instant.now();
    }

    public static IdempotencyRecord completed(String partnerId, String idempotencyKey,
                                              String requestFingerprint, Payment payment) {
        return new IdempotencyRecord(partnerId, idempotencyKey, requestFingerprint,
                Outcome.COMPLETED, payment, null, null);
    }

    public static IdempotencyRecord rejected(String partnerId, String idempotencyKey,
                                             String requestFingerprint,
                                             ErrorCode rejectionCode, String rejectionMessage) {
        return new IdempotencyRecord(partnerId, idempotencyKey, requestFingerprint,
                Outcome.REJECTED, null, rejectionCode, rejectionMessage);
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

    public ErrorCode rejectionCode() {
        return rejectionCode;
    }

    public String rejectionMessage() {
        return rejectionMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
