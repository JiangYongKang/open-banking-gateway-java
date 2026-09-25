package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 已生效支付指令（账本事实，一旦生成不可变）。
 */
public class Payment {

    private final String id;
    private final String consentId;
    private final String partnerId;
    private final String fromAccountId;
    private final String toAccountId;
    private final BigDecimal amount;
    private final String currency;
    private final Instant createdAt;

    public Payment(String consentId, String partnerId, String fromAccountId, String toAccountId,
                   BigDecimal amount, String currency) {
        this.id = UUID.randomUUID().toString();
        this.consentId = consentId;
        this.partnerId = partnerId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String consentId() {
        return consentId;
    }

    public String partnerId() {
        return partnerId;
    }

    public String fromAccountId() {
        return fromAccountId;
    }

    public String toAccountId() {
        return toAccountId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String currency() {
        return currency;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
