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
    private long version;
    private Instant updatedAt;

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
        this.version = 0;
        this.updatedAt = createdAt;
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

    /**
     * 对外次序信息：每次成功状态迁移单调递增，只增不减。
     */
    public long version() {
        return version;
    }

    /**
     * 最近一次成功变更（含创建）的时间。
     */
    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * 记录一次成功迁移：版本 +1、变更时间推进。须在同意书对象锁内调用。
     */
    public void markTransition(ConsentStatus target, Instant at) {
        this.status = target;
        this.version++;
        this.updatedAt = at;
    }
}
