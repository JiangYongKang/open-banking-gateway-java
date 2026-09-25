package com.github.highcumontoa.openbankinggatewayjava.account;

import com.github.highcumontoa.openbankinggatewayjava.security.PartnerPrincipal;
import com.github.highcumontoa.openbankinggatewayjava.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 账户查询 REST：GET /api/accounts/{accountId}?consentId=...
 */
@RestController
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    public record AccountView(String accountId, String currency, BigDecimal balance) {
        static AccountView of(Account a) {
            return new AccountView(a.getId(), a.getCurrency(), a.getBalance());
        }
    }

    @GetMapping("/api/accounts/{accountId}")
    public AccountView getAccount(@PathVariable String accountId,
                                  @RequestParam String consentId,
                                  HttpServletRequest request) {
        PartnerPrincipal principal = SecuritySupport.principal(request);
        SecuritySupport.requireScope(principal, AccountService.SCOPE_ACCOUNTS_READ);
        return AccountView.of(accountService.getAccount(principal.partnerId(), consentId, accountId));
    }
}
