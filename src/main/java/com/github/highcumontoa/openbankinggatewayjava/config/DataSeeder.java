package com.github.highcumontoa.openbankinggatewayjava.config;

import com.github.highcumontoa.openbankinggatewayjava.account.Account;
import com.github.highcumontoa.openbankinggatewayjava.account.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.ledger.LedgerService;
import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 本地种子数据（全部内置，无外部服务/真实凭据）。
 *
 * <p>令牌：
 * <ul>
 *   <li>partner-a：token-a-123，范围 consents / accounts.read / payments.create（资源属主）</li>
 *   <li>partner-b：token-b-456，与 partner-a 同范围但不拥有其资源（用于验证跨合作方稳定拒绝，
 *       响应与“资源不存在”完全一致，不泄漏归属）</li>
 *   <li>partner-c：token-c-789，仅 accounts.read（用于验证令牌范围不足 403）</li>
 * </ul>
 * 账户：ACCT-001、ACCT-002（EUR），ACCT-003（GBP）及对应清算账户。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    public static final long TOKEN_TTL_SECONDS = 86_400L;

    private final TokenService tokenService;
    private final AccountRepository accountRepository;

    public DataSeeder(TokenService tokenService, AccountRepository accountRepository) {
        this.tokenService = tokenService;
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(String... args) {
        tokenService.issue("token-a-123", "partner-a",
                Set.of("consents", "accounts.read", "payments.create"), TOKEN_TTL_SECONDS);
        tokenService.issue("token-b-456", "partner-b",
                Set.of("consents", "accounts.read", "payments.create"), TOKEN_TTL_SECONDS);
        tokenService.issue("token-c-789", "partner-c",
                Set.of("accounts.read"), TOKEN_TTL_SECONDS);

        accountRepository.save(new Account("ACCT-001", "EUR", new BigDecimal("1000.00")));
        accountRepository.save(new Account("ACCT-002", "EUR", new BigDecimal("500.00")));
        accountRepository.save(new Account("ACCT-003", "GBP", new BigDecimal("2000.00")));
        accountRepository.save(new Account(LedgerService.clearingAccount("EUR"), "EUR", BigDecimal.ZERO));
        accountRepository.save(new Account(LedgerService.clearingAccount("GBP"), "GBP", BigDecimal.ZERO));

        log.info("seed-data | partners=3 | accounts={} | tokens=token-a-123,token-b-456,token-c-789",
                accountRepository.findAll().size());
    }
}
