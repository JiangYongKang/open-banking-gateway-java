package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryAccountRepository implements AccountRepository {

    private final ConcurrentMap<String, Account> accounts = new ConcurrentHashMap<>();

    @Override
    public void save(Account account) {
        accounts.put(account.id(), account);
    }

    @Override
    public Optional<Account> findById(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public BigDecimal adjustBalance(String accountId, BigDecimal delta) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalStateException("account not found: " + accountId);
        }
        synchronized (account) {
            BigDecimal next = account.balance().add(delta);
            account.setBalance(next);
            return next;
        }
    }

    @Override
    public BigDecimal totalBalance() {
        return accounts.values().stream()
                .map(Account::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
