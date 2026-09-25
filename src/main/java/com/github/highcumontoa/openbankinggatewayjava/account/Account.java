package com.github.highcumontoa.openbankinggatewayjava.account;

import java.math.BigDecimal;

/**
 * 账户：币种、余额。余额仅允许账本服务在总账锁内修改，其余路径只读。
 */
public class Account {

    private final String id;
    private final String currency;
    private BigDecimal balance;

    public Account(String id, String currency, BigDecimal balance) {
        this.id = id;
        this.currency = currency;
        this.balance = balance;
    }

    public String getId() {
        return id;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
