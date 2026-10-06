package com.github.highcumontoa.openbankinggatewayjava.config;

import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import com.github.highcumontoa.openbankinggatewayjava.store.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

/**
 * 本地演示数据（无外部依赖）：
 * <ul>
 *   <li>账户 acct-1001 / acct-1002 / acct-2001，初始余额合计 100000 CNY；</li>
 *   <li>合作方 partner-alpha（账户+支付+同意书管理）、partner-beta（仅账户查询）、
 *       partner-gamma（同意书只读，模拟客服后台）；</li>
 *   <li>启动日志直接打印演示令牌，可复制使用。</li>
 * </ul>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    public static final String PARTNER_ALPHA = "partner-alpha";
    public static final String PARTNER_BETA = "partner-beta";
    public static final String PARTNER_GAMMA = "partner-gamma";

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AccountRepository accountRepository;
    private final TokenService tokenService;

    public DataInitializer(AccountRepository accountRepository, TokenService tokenService) {
        this.accountRepository = accountRepository;
        this.tokenService = tokenService;
    }

    @Override
    public void run(String... args) {
        accountRepository.save(new Account("acct-1001", "Alice Zhang", new BigDecimal("100000.00")));
        accountRepository.save(new Account("acct-1002", "Bob Li", new BigDecimal("0.00")));
        accountRepository.save(new Account("acct-2001", "Carol Wang", new BigDecimal("0.00")));

        TokenService.TokenRecord alpha = tokenService.issue(PARTNER_ALPHA,
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE,
                        Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE), Duration.ofHours(8));
        TokenService.TokenRecord beta = tokenService.issue(PARTNER_BETA,
                Set.of(Scope.ACCOUNTS_READ), Duration.ofHours(8));
        TokenService.TokenRecord gamma = tokenService.issue(PARTNER_GAMMA,
                Set.of(Scope.CONSENTS_READ), Duration.ofHours(8));

        log.info("==== 本地演示令牌（8h 有效）====");
        log.info("{} scopes={} token={}", PARTNER_ALPHA, alpha.scopes(), alpha.token());
        log.info("{} scopes={} token={}", PARTNER_BETA, beta.scopes(), beta.token());
        log.info("{} scopes={} token={}", PARTNER_GAMMA, gamma.scopes(), gamma.token());
        log.info("===============================");
    }
}
