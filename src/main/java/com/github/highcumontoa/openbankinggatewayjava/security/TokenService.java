package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌服务：本地内存令牌，不依赖网络与真实凭据。
 * 令牌缺失/非法 -> UNAUTHENTICATED(401)；令牌过期 -> TOKEN_EXPIRED(401)，二者可区分。
 */
@Service
public class TokenService {

    private final Map<String, PartnerPrincipal> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenService(Clock clock) {
        this.clock = clock;
    }

    /** 颁发（登记）一个本地令牌，仅供种子数据使用。 */
    public PartnerPrincipal issue(String tokenValue, String partnerId, Set<String> scopes, long ttlSeconds) {
        Instant expiresAt = clock.instant().plus(ttlSeconds, ChronoUnit.SECONDS);
        PartnerPrincipal principal = new PartnerPrincipal(partnerId, Set.copyOf(scopes), expiresAt, tokenValue);
        tokens.put(tokenValue, principal);
        return principal;
    }

    /**
     * 校验原始 Bearer 令牌：
     * @throws GatewayException UNAUTHENTICATED 令牌为空或不存在；TOKEN_EXPIRED 令牌已过期
     */
    public PartnerPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "missing bearer token");
        }
        PartnerPrincipal principal = tokens.get(rawToken);
        if (principal == null) {
            // 与过期保持同样的响应结构，仅 errorCode 不同；不提示令牌是否存在过
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "invalid token");
        }
        if (!clock.instant().isBefore(principal.expiresAt())) {
            throw new GatewayException(ErrorCode.TOKEN_EXPIRED,
                    "token expired at " + principal.expiresAt());
        }
        return principal;
    }
}
