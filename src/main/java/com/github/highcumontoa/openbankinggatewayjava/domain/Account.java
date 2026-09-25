package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.math.BigDecimal;

/**
 * 银行账户（仅内存账本演示用）。
 */
public class Account {

    private final String id;
    private final String ownerName;
    private BigDecimal balance;

    public Account(String id, String ownerName, BigDecimal openingBalance) {
        this.id = id;
        this.ownerName = ownerName;
        this.balance = openingBalance;
    }

    public String id() {
        return id;
    }

    public String ownerName() {
        return ownerName;
    }

    public BigDecimal balance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
