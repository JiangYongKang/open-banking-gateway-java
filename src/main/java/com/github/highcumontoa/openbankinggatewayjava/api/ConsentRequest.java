package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;

import java.util.Set;

/**
 * 创建同意书请求：授权账户集合 + 申请范围。有效期由服务端配置决定。
 */
public class ConsentRequest {

    private Set<String> accountIds;
    private Set<Scope> scopes;

    public ConsentRequest() {
    }

    public Set<String> getAccountIds() {
        return accountIds;
    }

    public void setAccountIds(Set<String> accountIds) {
        this.accountIds = accountIds;
    }

    public Set<Scope> getScopes() {
        return scopes;
    }

    public void setScopes(Set<Scope> scopes) {
        this.scopes = scopes;
    }
}
