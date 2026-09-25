package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.openbankinggatewayjava.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 Web 层测试：认证/过期/范围/归属无泄漏/非法迁移/幂等并发，均经 HTTP 语义断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final String A = "Bearer token-a-123"; // consents/accounts.read/payments.create
    private static final String B = "Bearer token-b-456"; // 同范围但不拥有 A 的资源
    private static final String C = "Bearer token-c-789"; // 仅 accounts.read

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TokenService tokenService;

    private String createAuthorisedConsent(String accountId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scopes", Set.of("accounts.read", "payments"),
                                "accountIds", Set.of(accountId)))))
                .andExpect(status().isOk())
                .andReturn();
        String consentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("consentId").asText();
        mockMvc.perform(post("/api/consents/" + consentId + "/authorize").header("Authorization", A))
                .andExpect(status().isOk());
        return consentId;
    }

    @Test
    void missingInvalidAndExpiredTokensAreDistinguishable() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "scopes", Set.of("accounts.read"), "accountIds", Set.of("ACCT-001")));

        mockMvc.perform(post("/api/consents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/consents").header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));

        // 颁发一个已过期令牌，错误码与 UNAUTHENTICATED 区分（同为 401）
        tokenService.issue("expired-token", "partner-a", Set.of("consents"), -10L);
        mockMvc.perform(post("/api/consents").header("Authorization", "Bearer expired-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("TOKEN_EXPIRED"));
    }

    @Test
    void insufficientScopeIsForbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "scopes", Set.of("accounts.read"), "accountIds", Set.of("ACCT-001")));
        mockMvc.perform(post("/api/consents").header("Authorization", C)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void crossPartnerAccessReturnsSameNotFoundAsMissing() throws Exception {
        String consentId = createAuthorisedConsent("ACCT-001");

        MvcResult cross = mockMvc.perform(get("/api/consents/" + consentId).header("Authorization", B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/consents/consent-missing").header("Authorization", B))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
                .andReturn();
        JsonNode crossNode = objectMapper.readTree(cross.getResponse().getContentAsString());
        JsonNode missingNode = objectMapper.readTree(missing.getResponse().getContentAsString());
        // 除时间戳外错误结构完全一致，杜绝通过响应差异推断资源归属
        org.assertj.core.api.Assertions.assertThat(crossNode.get("httpStatus").asInt())
                .isEqualTo(missingNode.get("httpStatus").asInt());
        org.assertj.core.api.Assertions.assertThat(crossNode.get("errorCode").asText())
                .isEqualTo(missingNode.get("errorCode").asText());
        org.assertj.core.api.Assertions.assertThat(crossNode.get("message").asText())
                .isEqualTo(missingNode.get("message").asText());
    }

    @Test
    void illegalTransitionOverHttpIs409() throws Exception {
        String consentId = createAuthorisedConsent("ACCT-001");
        mockMvc.perform(post("/api/consents/" + consentId + "/suspend").header("Authorization", A))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/consents/" + consentId + "/authorize").header("Authorization", A))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONSENT_TRANSITION"));
    }

    @Test
    void paymentIdempotencyAndConflictOverHttp() throws Exception {
        String consentId = createAuthorisedConsent("ACCT-002");
        String payload = objectMapper.writeValueAsString(Map.of(
                "consentId", consentId,
                "accountId", "ACCT-002",
                "creditorName", "Shop",
                "creditorAccount", "EXT-9",
                "amount", "12.34",
                "currency", "EUR"));

        MvcResult first = mockMvc.perform(post("/api/payments")
                        .header("Authorization", A).header("Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn();
        String paymentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("paymentId").asText();

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", A).header("Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.paymentId").value(paymentId));

        String different = objectMapper.writeValueAsString(Map.of(
                "consentId", consentId,
                "accountId", "ACCT-002",
                "creditorName", "Shop",
                "creditorAccount", "EXT-9",
                "amount", "99.00",
                "currency", "EUR"));
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", A).header("Idempotency-Key", "http-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(get("/api/accounts/ACCT-002")
                        .param("consentId", consentId)
                        .header("Authorization", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(487.66));
    }

    @Test
    void concurrentHttpDuplicatesApplyExactlyOnce() throws Exception {
        String consentId = createAuthorisedConsent("ACCT-003");
        String payload = objectMapper.writeValueAsString(Map.of(
                "consentId", consentId,
                "accountId", "ACCT-003",
                "creditorName", "Shop",
                "creditorAccount", "EXT-9",
                "amount", "7.00",
                "currency", "GBP"));

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> paymentIdRef = new java.util.concurrent.atomic.AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                MvcResult r = mockMvc.perform(post("/api/payments")
                                .header("Authorization", A).header("Idempotency-Key", "http-concurrent")
                                .contentType(MediaType.APPLICATION_JSON).content(payload))
                        .andReturn();
                JsonNode node = objectMapper.readTree(r.getResponse().getContentAsString());
                if (node.get("replayed").asBoolean()) {
                    replayed.incrementAndGet();
                } else {
                    created.incrementAndGet();
                    paymentIdRef.set(node.get("paymentId").asText());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        org.assertj.core.api.Assertions.assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        org.assertj.core.api.Assertions.assertThat(created.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(replayed.get()).isEqualTo(threads - 1);
        mockMvc.perform(get("/api/accounts/ACCT-003").param("consentId", consentId).header("Authorization", A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1993.00));
    }
}
