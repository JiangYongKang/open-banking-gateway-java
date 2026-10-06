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
 * 同意书"能看 / 能改"分层：
 * CONSENTS_READ 只读（列表+详情，含 usable/version/updatedAt），变更一律 403；
 * 跨合作方查看与不存在同构（404）；老调用方（无 CONSENTS_* 范围）能力不变。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentReadOnlyAccessTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TokenService tokenService;

    private String token(String partner, Set<Scope> scopes) {
        return tokenService.issue(partner, scopes, Duration.ofHours(1)).token();
    }

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][CONSENT-ACL] " + fmt.formatted(args));
    }

    private String createConsent(String manageToken, String scopesJson) throws Exception {
        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + manageToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[\"acct-1001\"],\"scopes\":" + scopesJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void readOnlyPartnerCanListAndViewOwnConsents() throws Exception {
        // gamma 名下同意书由管理流程建立；客服后台持只读令牌查看
        String gammaManage = token("partner-gamma",
                Set.of(Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE, Scope.ACCOUNTS_READ));
        String consentId = createConsent(gammaManage, "[\"ACCOUNTS_READ\"]");
        mockMvc.perform(post("/api/consents/{id}/authorize", consentId)
                        .header("Authorization", "Bearer " + gammaManage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        String gammaReadOnly = token("partner-gamma", Set.of(Scope.CONSENTS_READ));

        mockMvc.perform(get("/api/consents")
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].consentId").value(consentId))
                .andExpect(jsonPath("$[0].usable").value(true))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].updatedAt").exists());
        say("read-only list -> 200 consentId=%s usable=true version=1 basis=CONSENTS_READ-sufficient", consentId);

        mockMvc.perform(get("/api/consents/{id}", consentId)
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consentId").value(consentId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").exists());
        say("read-only detail -> 200 status=AUTHORIZED usable=true basis=own-consent-visible");
    }

    @Test
    void readOnlyPartnerIsRejectedOnAllMutations() throws Exception {
        String gammaManage = token("partner-gamma",
                Set.of(Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE, Scope.ACCOUNTS_READ));
        String consentId = createConsent(gammaManage, "[\"ACCOUNTS_READ\"]");
        String gammaReadOnly = token("partner-gamma", Set.of(Scope.CONSENTS_READ));

        String[] actions = {"authorize", "suspend", "resume", "revoke"};
        for (String action : actions) {
            mockMvc.perform(post("/api/consents/{id}/{action}", consentId, action)
                            .header("Authorization", "Bearer " + gammaReadOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_scope"));
            say("read-only %s -> 403 insufficient_scope basis=CONSENTS_MANAGE-missing", action);
        }
        mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + gammaReadOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[\"acct-1001\"],\"scopes\":[\"ACCOUNTS_READ\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_scope"));
        say("read-only create -> 403 insufficient_scope basis=CONSENTS_MANAGE-missing");

        // 只读令牌的所有变更尝试不得留下痕迹：状态仍是 CREATED
        mockMvc.perform(get("/api/consents/{id}", consentId)
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(0));
        say("after rejected mutations status=CREATED version=0 basis=reject-before-state-machine");
    }

    @Test
    void crossPartnerReadIsIdenticalToNotFound() throws Exception {
        String alphaManage = token("partner-alpha",
                Set.of(Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE, Scope.ACCOUNTS_READ));
        String consentId = createConsent(alphaManage, "[\"ACCOUNTS_READ\"]");
        String gammaReadOnly = token("partner-gamma", Set.of(Scope.CONSENTS_READ));

        mockMvc.perform(get("/api/consents/{id}", consentId)
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: " + consentId));
        mockMvc.perform(get("/api/consents/does-not-exist")
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: does-not-exist"));
        say("cross-partner detail vs absent -> identical 404 resource_not_found basis=ownership-hidden");

        // 列表只含自己名下：gamma 看不到 alpha 的同意书
        mockMvc.perform(get("/api/consents")
                        .header("Authorization", "Bearer " + gammaReadOnly))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.consentId == '" + consentId + "')]").doesNotExist());
        say("read-only list excludes others' consents basis=partner-scoped");
    }

    @Test
    void legacyCallerWithoutConsentScopesKeepsManageCapability() throws Exception {
        // 分层前的老调用方：令牌只有 ACCOUNTS_READ / PAYMENTS_INITIATE
        String legacy = token("partner-legacy", Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));
        String consentId = createConsent(legacy, "[\"ACCOUNTS_READ\"]");
        mockMvc.perform(post("/api/consents/{id}/authorize", consentId)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        mockMvc.perform(post("/api/consents/{id}/revoke", consentId)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(get("/api/consents/{id}", consentId)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.version").value(2));
        say("legacy caller create/authorize/revoke/detail all OK basis=no-CONSENTS_*-scopes=unrestricted");
    }
}
