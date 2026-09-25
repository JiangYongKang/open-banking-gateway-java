package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;

import java.math.BigDecimal;
import java.time.Instant;

public class PaymentResponse {

    private String paymentId;
    private String consentId;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private Instant createdAt;

    public static PaymentResponse from(Payment p) {
        PaymentResponse r = new PaymentResponse();
        r.paymentId = p.id();
        r.consentId = p.consentId();
        r.fromAccountId = p.fromAccountId();
        r.toAccountId = p.toAccountId();
        r.amount = p.amount();
        r.currency = p.currency();
        r.status = "COMPLETED";
        r.createdAt = p.createdAt();
        return r;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getConsentId() {
        return consentId;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public String getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
