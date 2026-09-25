package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Account;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountRepository {

    void save(Account account);

    Optional<Account> findById(String accountId);

    /**
     * 原子扣减/增加（delta 可负），返回更新后余额；账户不存在抛 IllegalStateException。
     */
    BigDecimal adjustBalance(String accountId, BigDecimal delta);

    /**
     * 全量账户余额之和：测试断言账本守恒用。
     */
    BigDecimal totalBalance();
}
