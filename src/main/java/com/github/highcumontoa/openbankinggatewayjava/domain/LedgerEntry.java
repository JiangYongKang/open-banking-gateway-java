package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 复式记账分录：每笔支付产生两条（付款方 CREDIT 记负 / 收款方 DEBIT 记正），
 * 同 paymentId 借贷必相等，保证账本可解释、总额守恒。
 */
public record LedgerEntry(
        String id,
        String paymentId,
        String accountId,
        Direction direction,
        BigDecimal amount,
        Instant createdAt
) {
    public enum Direction {
        DEBIT,
        CREDIT
    }
}
