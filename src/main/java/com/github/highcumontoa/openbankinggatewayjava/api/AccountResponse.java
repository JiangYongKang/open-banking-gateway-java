package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.Account;

import java.math.BigDecimal;

public class AccountResponse {

    private String accountId;
    private String ownerName;
    private BigDecimal balance;
    private String currency;

    public static AccountResponse from(Account account, String currency) {
        AccountResponse r = new AccountResponse();
        r.accountId = account.id();
        r.ownerName = account.ownerName();
        r.balance = account.balance();
        r.currency = currency;
        return r;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }
}
