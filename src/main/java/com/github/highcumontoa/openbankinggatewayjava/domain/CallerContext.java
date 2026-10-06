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

    /**
     * 是否可读取同意书（列表/详情）。
     * 老调用方（无任何 CONSENTS_* 范围）保持分层前的可见性。
     */
    public boolean canReadConsents() {
        return hasScope(Scope.CONSENTS_READ) || hasScope(Scope.CONSENTS_MANAGE) || isLegacyConsentCaller();
    }

    /**
     * 是否可变更同意书状态。
     * 只读令牌（有 CONSENTS_READ 无 CONSENTS_MANAGE）一律拒绝；
     * 老调用方（无任何 CONSENTS_* 范围）保持分层前的管理能力。
     */
    public boolean canManageConsents() {
        return hasScope(Scope.CONSENTS_MANAGE) || isLegacyConsentCaller();
    }

    private boolean isLegacyConsentCaller() {
        return !hasScope(Scope.CONSENTS_READ) && !hasScope(Scope.CONSENTS_MANAGE);
    }
}
