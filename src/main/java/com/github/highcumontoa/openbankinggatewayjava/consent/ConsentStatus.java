package com.github.highcumontoa.openbankinggatewayjava.consent;

/**
 * 同意书状态机：
 * <pre>
 *   AWAITING_AUTHORISATION --authorize--> AUTHORISED
 *   AUTHORISED --suspend--> SUSPENDED
 *   SUSPENDED --resume--> AUTHORISED
 *   任意存活状态 --revoke--> REVOKED（终态）
 *   到达 expiresAt 后懒迁移为 EXPIRED（终态）
 * </pre>
 * 终态 REVOKED / EXPIRED 不可再迁移。
 */
public enum ConsentStatus {
    AWAITING_AUTHORISATION,
    AUTHORISED,
    SUSPENDED,
    REVOKED,
    EXPIRED
}
