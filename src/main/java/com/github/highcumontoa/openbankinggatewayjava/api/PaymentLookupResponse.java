package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;

import java.time.Instant;

/**
 * 幂等键回查结果：成功（COMPLETED + payment）或业务拒绝（REJECTED + rejection）。
 * 拒绝原因原样返回首次判定的 code/message，不重新执行。
 */
public class PaymentLookupResponse {

    public static class Rejection {
        private final String code;
        private final String message;

        public Rejection(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }

    private String idempotencyKey;
    private String status;
    private PaymentResponse payment;
    private Rejection rejection;
    private Instant recordedAt;

    public static PaymentLookupResponse from(IdempotencyRecord record) {
        PaymentLookupResponse r = new PaymentLookupResponse();
        r.idempotencyKey = record.idempotencyKey();
        r.status = record.outcome().name();
        r.recordedAt = record.createdAt();
        if (record.outcome() == IdempotencyRecord.Outcome.COMPLETED) {
            r.payment = PaymentResponse.from(record.payment());
        } else {
            r.rejection = new Rejection(record.rejectionCode().code(), record.rejectionMessage());
        }
        return r;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getStatus() {
        return status;
    }

    public PaymentResponse getPayment() {
        return payment;
    }

    public Rejection getRejection() {
        return rejection;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
