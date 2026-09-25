package com.github.highcumontoa.openbankinggatewayjava.api;

import java.math.BigDecimal;

/**
 * 支付指令报文。幂等键由调用方通过请求头 Idempotency-Key 携带，
 * 故不放本报文体内，以便参与"同键不同报文"冲突判定。
 */
public class PaymentRequest {

    private String consentId;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String currency;

    public PaymentRequest() {
    }

    public String getConsentId() {
        return consentId;
    }

    public void setConsentId(String consentId) {
        this.consentId = consentId;
    }

    public String getFromAccountId() {
        return fromAccountId;
    }

    public void setFromAccountId(String fromAccountId) {
        this.fromAccountId = fromAccountId;
    }

    public String getToAccountId() {
        return toAccountId;
    }

    public void setToAccountId(String toAccountId) {
        this.toAccountId = toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
