package com.github.highcumontoa.openbankinggatewayjava.account;

import com.github.highcumontoa.openbankinggatewayjava.consent.Consent;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账户查询服务：在同意书锁内完成“有效同意书 + accounts.read 范围 + 账户在授权清单内”判定，
 * 保证判定与读取之间同意书状态不会被撤销/过期改变。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    public static final String SCOPE_ACCOUNTS_READ = "accounts.read";

    private final AccountRepository accountRepository;
    private final ConsentService consentService;

    public AccountService(AccountRepository accountRepository, ConsentService consentService) {
        this.accountRepository = accountRepository;
        this.consentService = consentService;
    }

    public Account getAccount(String partnerId, String consentId, String accountId) {
        synchronized (consentService.lockOf(consentId)) {
            // requireUsable 内部对不存在/跨合作方同意书统一抛 NOT_FOUND，对过期/撤销/范围不符抛可区分错误
            Consent consent = consentService.requireUsable(partnerId, consentId, SCOPE_ACCOUNTS_READ, accountId);
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null) {
                // 账户不存在：对调用方同样返回 NOT_FOUND，不泄漏任何存在性信息
                log.warn("account-access | partner={} | consentId={} | account={} | verdict=DENY | reason=account-not-found",
                        partnerId, consentId, accountId);
                throw new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, "resource not found");
            }
            log.info("account-access | partner={} | consentId={} | account={} | consentStatus={} | verdict=ALLOW",
                    partnerId, consentId, accountId, consent.getStatus());
            return account;
        }
    }
}
