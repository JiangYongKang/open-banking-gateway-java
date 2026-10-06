package com.github.highcumontoa.openbankinggatewayjava.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 同意书的一致性快照：在对象锁内一次性读出，
 * 保证 status / version / updatedAt / usable 来自同一次状态迁移之后，
 * 对外读取不会撕裂（不会看到"状态已撤销但版本未推进"之类的混合态）。
 */
public record ConsentSnapshot(
        String id,
        String partnerId,
        Set<String> accountIds,
        Set<Scope> scopes,
        Instant createdAt,
        Instant expiresAt,
        ConsentStatus status,
        long version,
        Instant updatedAt,
        boolean usable) {
}
