package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 本地便利端点（不走 /api/**，无需认证）：查看内存中已签发令牌，仅演示用。
 */
@RestController
public class LocalTokenController {

    public record TokenView(String partnerId, String token, List<String> scopes, Instant expiresAt) {
    }

    private final TokenService tokenService;

    public LocalTokenController(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/local/tokens")
    public List<TokenView> list() {
        return tokenService.allTokens().stream()
                .map(t -> new TokenView(t.partnerId(), t.token(),
                        t.scopes().stream().map(Enum::name).sorted().toList(), t.expiresAt()))
                .toList();
    }
}
