package com.github.highcumontoa.openbankinggatewayjava.service;

import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.store.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账户/余额查询：必须持有 AUTHORIZED 且含 ACCOUNTS_READ 的同意书，账户在授权清单内。
 */
@Service
public class AccountQueryService {

    private static final Logger log = LoggerFactory.getLogger(AccountQueryService.class);

    private final ConsentService consentService;
    private final AccountRepository accountRepository;

    public AccountQueryService(ConsentService consentService, AccountRepository accountRepository) {
        this.consentService = consentService;
        this.accountRepository = accountRepository;
    }

    public Account getAccount(CallerContext caller, String consentId, String accountId) {
        Consent consent = consentService.requireUsable(caller, consentId, Scope.ACCOUNTS_READ);
        consentService.requireAccountInConsent(consent, accountId);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new GatewayException(ErrorCode.ACCOUNT_NOT_FOUND,
                        "account not found: " + accountId));
        log.info("account-read partner={} consentId={} accountId={} balance={}",
                caller.partnerId(), consentId, accountId, account.balance());
        return account;
    }
}
