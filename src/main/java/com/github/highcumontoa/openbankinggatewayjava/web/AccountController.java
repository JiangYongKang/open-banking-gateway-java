package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.api.AccountResponse;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.security.CallerHolder;
import com.github.highcumontoa.openbankinggatewayjava.service.AccountQueryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户/余额查询：令牌须含 ACCOUNTS_READ，且同意书授权该账户。
 */
@RestController
public class AccountController {

    private final AccountQueryService accountQueryService;
    private final GatewayProperties properties;

    public AccountController(AccountQueryService accountQueryService, GatewayProperties properties) {
        this.accountQueryService = accountQueryService;
        this.properties = properties;
    }

    @GetMapping("/api/accounts/{accountId}")
    public AccountResponse getAccount(HttpServletRequest http,
                                      @org.springframework.web.bind.annotation.PathVariable String accountId,
                                      @RequestParam("consentId") String consentId) {
        CallerContext caller = CallerHolder.require(http);
        if (!caller.hasScope(Scope.ACCOUNTS_READ)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE, "token requires scope ACCOUNTS_READ");
        }
        return AccountResponse.from(accountQueryService.getAccount(caller, consentId, accountId),
                properties.getCurrency());
    }
}
