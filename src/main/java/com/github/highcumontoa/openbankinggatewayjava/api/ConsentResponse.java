package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;

import java.time.Instant;
import java.util.Set;

/**
 * 同意书对外视图。version / updatedAt / usable 为新增字段，
 * 属纯增量，老客户端按原字段解析不受影响。
 */
public class ConsentResponse {

    private String consentId;
    private String status;
    private Set<String> accountIds;
    private Set<Scope> scopes;
    private Instant createdAt;
    private Instant expiresAt;
    private long version;
    private Instant updatedAt;
    private boolean usable;

    public static ConsentResponse from(Consent c) {
        return from(c, c.status() == com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus.AUTHORIZED
                && c.expiresAt().isAfter(Instant.now()));
    }

    /**
     * 从锁内快照构造：status/version/updatedAt/usable 保证来自同一时点，不撕裂。
     */
    public static ConsentResponse from(com.github.highcumontoa.openbankinggatewayjava.domain.ConsentSnapshot s) {
        ConsentResponse r = new ConsentResponse();
        r.consentId = s.id();
        r.status = s.status().name();
        r.accountIds = s.accountIds();
        r.scopes = s.scopes();
        r.createdAt = s.createdAt();
        r.expiresAt = s.expiresAt();
        r.version = s.version();
        r.updatedAt = s.updatedAt();
        r.usable = s.usable();
        return r;
    }

    public static ConsentResponse from(Consent c, boolean usable) {
        ConsentResponse r = new ConsentResponse();
        r.consentId = c.id();
        r.status = c.status().name();
        r.accountIds = c.accountIds();
        r.scopes = c.scopes();
        r.createdAt = c.createdAt();
        r.expiresAt = c.expiresAt();
        r.version = c.version();
        r.updatedAt = c.updatedAt();
        r.usable = usable;
        return r;
    }

    public String getConsentId() {
        return consentId;
    }

    public String getStatus() {
        return status;
    }

    public Set<String> getAccountIds() {
        return accountIds;
    }

    public Set<Scope> getScopes() {
        return scopes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isUsable() {
        return usable;
    }
}
