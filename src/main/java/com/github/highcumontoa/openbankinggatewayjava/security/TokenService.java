package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存令牌服务（不依赖任何外部授权服务器）。
 * 令牌格式仅为演示：opaque hex，服务端持有 partnerId/scopes/过期时间。
 */
@Service
public class TokenService {

    public record TokenRecord(String token, String partnerId, Set<Scope> scopes, Instant expiresAt) {
    }

    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TokenRecord issue(String partnerId, Set<Scope> scopes, Duration ttl) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String token = "obg_" + HexFormat.of().formatHex(buf);
        TokenRecord record = new TokenRecord(token, partnerId, Set.copyOf(scopes), Instant.now().plus(ttl));
        tokens.put(token, record);
        return record;
    }

    /**
     * 校验令牌并解析调用方身份。无令牌/伪造 → UNAUTHENTICATED；过期 → TOKEN_EXPIRED。
     */
    public CallerContext authenticate(String authorizationHeader) {
        String token = extract(authorizationHeader)
                .orElseThrow(() -> new GatewayException(ErrorCode.UNAUTHENTICATED, "missing bearer token"));
        TokenRecord record = tokens.get(token);
        if (record == null) {
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "invalid token");
        }
        if (!record.expiresAt().isAfter(Instant.now())) {
            throw new GatewayException(ErrorCode.TOKEN_EXPIRED, "token expired at " + record.expiresAt());
        }
        return new CallerContext(record.partnerId(), record.scopes());
    }

    /** 仅供本地演示端点列举已签发令牌。 */
    public java.util.Collection<TokenRecord> allTokens() {
        return java.util.List.copyOf(tokens.values());
    }

    private Optional<String> extract(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    // 便于测试/本地启动直接构造过期令牌
    public TokenRecord issueWithExpiry(String partnerId, Set<Scope> scopes, Instant expiresAt) {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        String token = "obg_" + HexFormat.of().formatHex(buf);
        TokenRecord record = new TokenRecord(token, partnerId, Set.copyOf(scopes), expiresAt);
        tokens.put(token, record);
        return record;
    }
}
