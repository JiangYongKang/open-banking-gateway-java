package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 客户同意书：归属某合作方，限定可访问账户集合、授权范围与有效期。
 *
 * 一致性语义：每次成功的状态迁移在同意书对象锁内原子地推进
 * {@code version}（只增不减）与 {@code lastModifiedAt}；
 * 字段均为 volatile，锁外读取（详情/列表/支付判定）总能看到最近一次成功迁移后的状态。
 */
public class Consent {

    private final String id;
    private final String partnerId;
    private final Set<String> accountIds;
    private final Set<Scope> scopes;
    private final Instant createdAt;
    private final Instant expiresAt;

    private volatile ConsentStatus status;
    private volatile long version;
    private volatile Instant lastModifiedAt;

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
        this.lastModifiedAt = createdAt;
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

    /** 对外次序信息：每次成功迁移 +1，只增不减。 */
    public long version() {
        return version;
    }

    /** 最近一次成功变更（含创建）的时间。 */
    public Instant lastModifiedAt() {
        return lastModifiedAt;
    }

    /**
     * 即时有效状态：REVOKED 恒为终态；超过 expiresAt 视为 EXPIRED（不主动迁移字段）。
     * 读取的是 volatile 字段，总能看到最近一次成功迁移之后的状态。
     */
    public ConsentStatus effectiveStatus() {
        if (status == ConsentStatus.REVOKED) {
            return ConsentStatus.REVOKED;
        }
        if (!expiresAt.isAfter(Instant.now())) {
            return ConsentStatus.EXPIRED;
        }
        return status;
    }

    /** 当前是否可用于账户查询/支付判定。 */
    public boolean usable() {
        return effectiveStatus() == ConsentStatus.AUTHORIZED;
    }

    /**
     * 成功迁移时原子推进状态三元组。调用方必须持有本对象锁。
     */
    public void markTransition(ConsentStatus target, Instant at) {
        this.version = this.version + 1;
        this.lastModifiedAt = at;
        this.status = target;
    }

    public void setStatus(ConsentStatus status) {
        this.status = status;
    }
}
