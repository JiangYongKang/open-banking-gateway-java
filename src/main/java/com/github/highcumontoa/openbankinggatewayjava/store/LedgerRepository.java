package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.LedgerEntry;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerRepository {

    void append(LedgerEntry entry);

    List<LedgerEntry> findAll();

    /**
     * 全部分录金额之和（DEBIT 为正，CREDIT 为负），恒应为 0。
     */
    BigDecimal signedTotal();
}
