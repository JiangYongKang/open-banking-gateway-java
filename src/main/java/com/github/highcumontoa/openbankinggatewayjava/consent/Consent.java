package com.github.highcumontoa.openbankinggatewayjava.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * 同意书聚合：归属合作方、授权范围、账户范围、状态与过期时间。
 * 状态迁移只允许经 ConsentService 在该同意书的锁内进行。
 */
public class Consent {

    private final String id;
    private final String partnerId;
    private final Set<String> scopes;
    private final Set<String> accountIds;
    private final Instant createdAt;
    private final Instant expiresAt;
    private volatile ConsentStatus status;
    private volatile Instant updatedAt;

    public Consent(String id, String partnerId, Set<String> scopes, Set<String> accountIds,
                   Instant createdAt, Instant expiresAt, ConsentStatus status) {
        this.id = id;
        this.partnerId = partnerId;
        this.scopes = Set.copyOf(scopes);
        this.accountIds = Set.copyOf(accountIds);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = status;
        this.updatedAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getPartnerId() {
        return partnerId;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public Set<String> getAccountIds() {
        return accountIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ConsentStatus getStatus() {
        return status;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 状态迁移并刷新 updatedAt；调用方必须持有该同意书的锁。 */
    public void changeStatus(ConsentStatus next, Clock clock) {
        this.status = next;
        this.updatedAt = clock.instant();
    }

    /** 是否到达过期时刻（仍需状态机判定可否迁移到 EXPIRED）。 */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
