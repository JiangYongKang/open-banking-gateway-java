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

    public static ConsentResponse from(Consent c) {
        ConsentResponse r = new ConsentResponse();
        r.consentId = c.id();
        r.status = c.status().name();
        r.accountIds = c.accountIds();
        r.scopes = c.scopes();
        r.createdAt = c.createdAt();
        r.expiresAt = c.expiresAt();
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
}
