package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;

import java.time.Instant;

/**
 * 按幂等键回查的对外视图：首次提交的最终判定。
 * outcome=COMPLETED 时带支付结果；outcome=REJECTED 时带当时判定的可区分原因。
 */
public class PaymentLookupResponse {

    private String idempotencyKey;
    private String outcome;
    private PaymentResponse payment;
    private String rejectReason;
    private String rejectMessage;
    private Instant recordedAt;

    public static PaymentLookupResponse from(IdempotencyRecord record) {
        PaymentLookupResponse r = new PaymentLookupResponse();
        r.idempotencyKey = record.idempotencyKey();
        r.outcome = record.outcome().name();
        if (record.outcome() == IdempotencyRecord.Outcome.COMPLETED) {
            r.payment = PaymentResponse.from(record.payment());
        } else {
            r.rejectReason = record.rejectReason().code();
            r.rejectMessage = record.rejectMessage();
        }
        r.recordedAt = record.createdAt();
        return r;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOutcome() {
        return outcome;
    }

    public PaymentResponse getPayment() {
        return payment;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public String getRejectMessage() {
        return rejectMessage;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
