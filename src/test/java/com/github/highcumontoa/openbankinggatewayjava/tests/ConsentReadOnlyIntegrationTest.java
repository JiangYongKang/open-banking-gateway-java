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
 * 只读合作方（CONSENTS_READ）可列表/详情，变更一律 403；
 * 跨合作方读取与不存在同构（404）；旧令牌（无分层 scope）维持可管理的兼容行为。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsentReadOnlyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TokenService tokenService;

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][CONSENT-TIER] " + fmt.formatted(args));
    }

    private String token(String partner, Set<Scope> scopes) {
        return tokenService.issue(partner, scopes, Duration.ofHours(1)).token();
    }

    private String createAuthorizedConsent(String manageToken) throws Exception {
        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + manageToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountIds":["acct-1001"],"scopes":["ACCOUNTS_READ"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/consents/{id}/authorize", id)
                        .header("Authorization", "Bearer " + manageToken))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void readOnlyPartnerCanListAndSeeDetailButNotMutate() throws Exception {
        String manage = token("partner-owner",
                Set.of(Scope.ACCOUNTS_READ, Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE));
        String readOnly = token("partner-viewer", Set.of(Scope.CONSENTS_READ));

        String id = createAuthorizedConsent(manage);
        say("created+authorized consentId=%s owner=partner-owner", id);

        // 只读合作方查看自己名下（空）列表：允许
        mockMvc.perform(get("/api/consents").header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isOk());
        say("read-only GET /api/consents -> 200 basis=CONSENTS_READ-allows-list");

        // 只读合作方对自己的资源做变更：一律 403
        String ownId = null;
        // 只读合作方不能创建
        mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + readOnly)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountIds":["acct-1001"],"scopes":["ACCOUNTS_READ"]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient_scope"));
        say("read-only POST /api/consents -> 403 insufficient_scope basis=mutation-requires-CONSENTS_MANAGE");

        for (String action : new String[]{"authorize", "suspend", "resume", "revoke"}) {
            mockMvc.perform(post("/api/consents/{id}/" + action, id)
                            .header("Authorization", "Bearer " + readOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("insufficient_scope"));
            say("read-only POST /api/consents/%s/%s -> 403 insufficient_scope basis=mutation-blocked", id, action);
        }

        // 只读合作方看别人的同意书详情：404，与不存在同构
        mockMvc.perform(get("/api/consents/{id}", id)
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: " + id));
        mockMvc.perform(get("/api/consents/{id}", "no-such-consent")
                        .header("Authorization", "Bearer " + readOnly))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"))
                .andExpect(jsonPath("$.message").value("consent not found: no-such-consent"));
        say("read-only cross-partner/absent detail -> identical 404 basis=ownership-hidden");
    }

    @Test
    void ownerSeesUsableFlagAndVersionInListAndDetail() throws Exception {
        String manage = token("partner-owner2",
                Set.of(Scope.ACCOUNTS_READ, Scope.CONSENTS_READ, Scope.CONSENTS_MANAGE));
        String id = createAuthorizedConsent(manage);

        mockMvc.perform(get("/api/consents/{id}", id).header("Authorization", "Bearer " + manage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.lastModifiedAt").exists());
        say("owner detail consentId=%s -> status=AUTHORIZED usable=true version=1 basis=one-transition-applied", id);

        mockMvc.perform(get("/api/consents").header("Authorization", "Bearer " + manage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.consentId=='" + id + "')].usable").value(true));

        // 撤销后：version 递增、usable 变 false
        mockMvc.perform(post("/api/consents/{id}/revoke", id).header("Authorization", "Bearer " + manage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/consents/{id}", id).header("Authorization", "Bearer " + manage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.version").value(2));
        say("owner detail after-revoke consentId=%s -> status=REVOKED usable=false version=2 basis=version-monotonic", id);
    }

    @Test
    void legacyTokenWithoutTierScopesKeepsManageAbility() throws Exception {
        // 分层前签发的旧令牌：只有业务 scope，没有任何 CONSENTS_* scope
        String legacy = token("partner-legacy", Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));

        String created = mockMvc.perform(post("/api/consents")
                        .header("Authorization", "Bearer " + legacy)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountIds":["acct-1001"],"scopes":["ACCOUNTS_READ"]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"consentId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/consents/{id}/authorize", id)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/consents/{id}", id)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/consents/{id}/revoke", id)
                        .header("Authorization", "Bearer " + legacy))
                .andExpect(status().isOk());
        say("legacy-token consentId=%s create/authorize/read/revoke all-ok basis=no-tier-scopes-grandfathered", id);
    }
}
