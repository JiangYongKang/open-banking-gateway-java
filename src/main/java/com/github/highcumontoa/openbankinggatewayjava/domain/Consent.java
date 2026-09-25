package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 客户同意书：归属某合作方，限定可访问账户集合、授权范围与有效期。
 */
public class Consent {

    private final String id;
    private final String partnerId;
    private final Set<String> accountIds;
    private final Set<Scope> scopes;
    private final Instant createdAt;
    private final Instant expiresAt;

    private ConsentStatus status;

    public Consent(String partnerId, Set<String> accountIds, Set<Scope> scopes, Instant expiresAt) {
        this(UUID.randomUUID().toString(), partnerId, accountIds, scopes, Instant.now(), expiresAt, ConsentStatus.CREATED);
    }

    public Consent(String id, String partnerId, Set<String> accountIds, Set<Scope> scopes,
                   Instant createdAt, Instant expiresAt, ConsentStatus status) {
        this.id = id;
        this.partnerId = partnerId;
        this.accountIds = Set.copyOf(accountIds);
        this.scopes = Set.copyOf(scopes);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
    }

    public String id() {
        return id;
    }

    public String partnerId() {
        return partnerId;
    }

    public Set<String> accountIds() {
        return accountIds;
    }

    public Set<Scope> scopes() {
        return scopes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public ConsentStatus status() {
        return status;
    }

    public void setStatus(ConsentStatus status) {
        this.status = status;
    }
}
