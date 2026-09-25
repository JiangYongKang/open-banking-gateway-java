package com.github.highcumontoa.openbankinggatewayjava.ledger;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可变账本条目：一笔支付产生两条配对条目（付款方贷记为负 / 清算账户借记为正），金额守恒。
 */
public record LedgerEntry(
        String id,
        String paymentId,
        String accountId,
        String direction,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {
}
