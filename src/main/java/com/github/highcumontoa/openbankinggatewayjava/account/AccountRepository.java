package com.github.highcumontoa.openbankinggatewayjava.account;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户内存仓储（含种子数据）。
 */
@Repository
public class AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public Optional<Account> findById(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public Account save(Account account) {
        accounts.put(account.getId(), account);
        return account;
    }

    public Collection<Account> findAll() {
        return accounts.values();
    }
}
