package com.github.highcumontoa.openbankinggatewayjava.api;

import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;

import java.time.Instant;
import java.util.Set;

public class ConsentResponse {

    private String consentId;
    private String status;
    private Set<String> accountIds;
    private Set<Scope> scopes;
    private Instant createdAt;
    private Instant expiresAt;
    // 新增字段（纯追加，旧调用方不受影响）：对外次序信息与可用性
    private long version;
    private Instant lastModifiedAt;
    private boolean usable;

    public static ConsentResponse from(Consent c) {
        ConsentResponse r = new ConsentResponse();
        r.consentId = c.id();
        r.status = c.effectiveStatus().name();
        r.accountIds = c.accountIds();
        r.scopes = c.scopes();
        r.createdAt = c.createdAt();
        r.expiresAt = c.expiresAt();
        r.version = c.version();
        r.lastModifiedAt = c.lastModifiedAt();
        r.usable = c.usable();
        return r;
    }

    public long getVersion() {
        return version;
    }

    public Instant getLastModifiedAt() {
        return lastModifiedAt;
    }

    public boolean isUsable() {
        return usable;
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
}
