package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.util.Set;

/**
 * 经令牌解析出的调用方身份：合作方标识 + 授权范围集合。
 */
public class CallerContext {

    private final String partnerId;
    private final Set<Scope> scopes;

    public CallerContext(String partnerId, Set<Scope> scopes) {
        this.partnerId = partnerId;
        this.scopes = Set.copyOf(scopes);
    }

    public String partnerId() {
        return partnerId;
    }

    public Set<Scope> scopes() {
        return scopes;
    }

    public boolean hasScope(Scope scope) {
        return scopes.contains(scope);
    }
}
