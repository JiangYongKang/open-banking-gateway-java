package com.github.highcumontoa.openbankinggatewayjava.domain;

/**
 * 同意书状态机：
 * <pre>
 *   CREATED --authorize--> AUTHORIZED
 *   AUTHORIZED --suspend--> SUSPENDED
 *   SUSPENDED --resume--> AUTHORIZED
 *   CREATED/AUTHORIZED/SUSPENDED --revoke--> REVOKED
 *   任何状态到期后视为 EXPIRED（不主动迁移，判定时即时计算）
 * </pre>
 */
public enum ConsentStatus {
    CREATED,
    AUTHORIZED,
    SUSPENDED,
    REVOKED,
    EXPIRED
}
