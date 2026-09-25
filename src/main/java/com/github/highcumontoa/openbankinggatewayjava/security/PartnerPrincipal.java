package com.github.highcumontoa.openbankinggatewayjava.security;

import java.time.Instant;
import java.util.Set;

/**
 * 调用方身份：合作方标识及其被授予的授权范围。
 */
public record PartnerPrincipal(
        String partnerId,
        Set<String> scopes,
        Instant expiresAt,
        String tokenValue
) {
}
