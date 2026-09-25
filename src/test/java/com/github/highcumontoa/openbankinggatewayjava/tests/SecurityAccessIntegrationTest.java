package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端身份/授权校验：无令牌、过期令牌、范围不足、跨合作方访问、状态非法。
 * 不存在与跨方访问对外响应同构（404 + resource_not_found）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TokenService tokenService;

    private String token(String partner, Set<Scope> scopes) {
        return tokenService.issue(partner, scopes, java.time.Duration.ofHours(1)).token();
    }

    private String expiredToken(String partner, Set<Scope> scopes) {
        return tokenService.issueWithExpiry(partner, scopes, Instant.now().minusSeconds(60)).token();
    }

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][SECURITY] " + fmt.formatted(args));
    }

    @Test
    void missingAndInvalidAndExpiredTokensAreStableRejections() throws Exception {
        mockMvc.perform(get("/api/accounts/acct-1001").param("consentId", "x"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        say("no-token -> 401 unauthenticated basis=missing-bearer");

        mockMvc.perform(get("/api/accounts/acct-1001").param("consentId", "x")
                        .header("Authorization", "Bearer obg_not_a_real_token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        say("forged-token -> 401 unauthenticated basis=unknown-token");

        mockMvc.perform(get("/api/accounts/acct-1001").param("consentId", "x")
                        .header("Authorization", "Bearer " + expiredToken("p", Set.of(Scope.ACCOUNTS_READ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("token_expired"));
        say("expired-token -> 401 token_expired basis=expiresAt<now");
    }

    @Test
    void insufficientScopeIs403AndDoesNotLeakExistence() throws Exception {
        String readOnlyToken = token("partner-readonly", Set.of(Scope.ACCOUNTS_READ));

        // 支付范围不足
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + readOnlyToken)
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentId":"c","fromAccountId":"a","toAccountId":"b","amount":1,"currency":"CNY"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_scope"));
        say("read-only-token payments -> 403 insufficient_scope basis=token-lacks-PAYMENTS_INITIATE");
    }

    @Test
    void crossPartnerAndAbsentConsentReturnIdentical404() throws Exception {
        String alpha = token("partner-alpha",
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));
        String beta = token("partner-beta", Set.of(Scope.ACCOUNTS_READ));

        String body = """
                {"accountIds":["acct-1001"],"scopes":["ACCOUNTS_READ"]}
                """;
        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + alpha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");
        say("created consentId=%s owner=partner-alpha", id);

        // 合作方 beta 访问 alpha 的资源
        mockMvc.perform(post("/api/consents/{id}/suspend", id)
                        .header("Authorization", "Bearer " + beta))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: " + id));
        say("cross-partner access -> 404 resource_not_found message='consent not found: %s' basis=ownership-hidden", id);

        // 访问不存在资源，响应同构
        mockMvc.perform(post("/api/consents/does-not-exist/suspend")
                        .header("Authorization", "Bearer " + beta))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: does-not-exist"));
        say("absent access -> 404 resource_not_found basis=identical-shape");
    }

    @Test
    void paymentOnSuspendedConsentIsConflictAndMovesNoMoney() throws Exception {
        String alpha = token("partner-alpha",
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));

        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + alpha)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountIds":["acct-1001","acct-1002"],"scopes":["PAYMENTS_INITIATE"]}
                                """))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/consents/{id}/authorize", id)
                .header("Authorization", "Bearer " + alpha)).andExpect(status().isOk());
        mockMvc.perform(post("/api/consents/{id}/suspend", id)
                .header("Authorization", "Bearer " + alpha)).andExpect(status().isOk());

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "suspended-pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consentId":"%s","fromAccountId":"acct-1001","toAccountId":"acct-1002",
                                 "amount":10,"currency":"CNY"}
                                """.formatted(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_consent_state"));
        say("payment on SUSPENDED consent -> 409 invalid_consent_state basis=status!=AUTHORIZED");
    }
}
