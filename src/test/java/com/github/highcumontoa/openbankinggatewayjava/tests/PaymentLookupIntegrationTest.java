package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 支付回查端点端到端：成功/失败都可按幂等键回查；
 * 同键异报文 409；无支付范围 403；未知键 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentLookupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TokenService tokenService;

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][LOOKUP-API] " + fmt.formatted(args));
    }

    private String token(String partner, Set<Scope> scopes) {
        return tokenService.issue(partner, scopes, Duration.ofHours(1)).token();
    }

    private String paymentBody(String consentId, String amount) {
        return "{\"consentId\":\"" + consentId
                + "\",\"fromAccountId\":\"acct-1001\",\"toAccountId\":\"acct-1002\","
                + "\"amount\":" + amount + ",\"currency\":\"CNY\"}";
    }

    private String authorizedConsent(String manageToken) throws Exception {
        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + manageToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[\"acct-1001\",\"acct-1002\"],"
                                + "\"scopes\":[\"PAYMENTS_INITIATE\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/consents/{id}/authorize", id)
                        .header("Authorization", "Bearer " + manageToken))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void successAndFailureAreBothLookupableByIdempotencyKey() throws Exception {
        String alpha = token("partner-alpha",
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE,
                        Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE));
        String consentId = authorizedConsent(alpha);

        // 成功一笔
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "api-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(consentId, "1.00")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/payments/lookup")
                        .param("idempotencyKey", "api-ok-1")
                        .header("Authorization", "Bearer " + alpha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.payment.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payment.amount").value(1.00));
        say("lookup api-ok-1 -> 200 outcome=COMPLETED basis=stored-success");

        // 余额不足失败一笔（acct-1001 余额不足以支付 9 亿）
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "api-fail-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(consentId, "900000000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient_funds"));
        mockMvc.perform(get("/api/payments/lookup")
                        .param("idempotencyKey", "api-fail-1")
                        .header("Authorization", "Bearer " + alpha))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("insufficient_funds"));
        say("lookup api-fail-1 -> 200 outcome=REJECTED reason=insufficient_funds basis=recorded-rejection");

        // 同键异报文：409 冲突，不能靠改报文绕过失败
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "api-fail-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(consentId, "0.50")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_conflict"));
        say("resubmit api-fail-1 different-amount -> 409 idempotency_conflict basis=fingerprint-differs");

        // 同键同报文：原样回放首次失败
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "api-fail-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(consentId, "900000000.00")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient_funds"));
        say("resubmit api-fail-1 same-payload -> 422 insufficient_funds basis=replay-original-decision");
    }

    @Test
    void lookupScopeAndNotFoundSemantics() throws Exception {
        String alpha = token("partner-alpha",
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE,
                        Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE));
        String readOnly = token("partner-beta", Set.of(Scope.ACCOUNTS_READ));
        String consentId = authorizedConsent(alpha);

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", "Bearer " + alpha)
                        .header("Idempotency-Key", "api-scope-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(consentId, "1.00")))
                .andExpect(status().isCreated());

        // 无 PAYMENTS_INITIATE 的令牌不能回查
        mockMvc.perform(get("/api/payments/lookup")
                        .param("idempotencyKey", "api-scope-1")
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_scope"));
        say("lookup without PAYMENTS_INITIATE -> 403 insufficient_scope basis=scope-required");

        // 未知键与他人键：404 同构
        mockMvc.perform(get("/api/payments/lookup")
                        .param("idempotencyKey", "api-never-seen")
                        .header("Authorization", "Bearer " + alpha))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"));
        String gamma = token("partner-gamma", Set.of(Scope.PAYMENTS_INITIATE));
        mockMvc.perform(get("/api/payments/lookup")
                        .param("idempotencyKey", "api-scope-1")
                        .header("Authorization", "Bearer " + gamma))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"));
        say("lookup unknown-key and cross-partner-key -> identical 404 basis=key-scoped-by-partner");
    }
}
