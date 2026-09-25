package com.github.highcumontoa.openbankinggatewayjava.consent;

import com.github.highcumontoa.openbankinggatewayjava.security.PartnerPrincipal;
import com.github.highcumontoa.openbankinggatewayjava.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Set;

/**
 * 同意书 REST 接口。
 * 写操作需要 scope=consents；所有端点自动做调用方认证与合作方归属校验。
 */
@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    public static final String SCOPE_CONSENTS = "consents";

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    public record CreateConsentRequest(Set<String> scopes, Set<String> accountIds) {
    }

    public record ConsentView(
            String consentId,
            String status,
            Set<String> scopes,
            Set<String> accountIds,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt) {

        static ConsentView of(Consent c) {
            return new ConsentView(c.getId(), c.getStatus().name(), c.getScopes(), c.getAccountIds(),
                    c.getCreatedAt(), c.getUpdatedAt(), c.getExpiresAt());
        }
    }

    @PostMapping
    public ConsentView create(@RequestBody CreateConsentRequest request, HttpServletRequest httpRequest) {
        PartnerPrincipal principal = SecuritySupport.principal(httpRequest);
        SecuritySupport.requireScope(principal, SCOPE_CONSENTS);
        Consent consent = consentService.create(principal.partnerId(), request.scopes(), request.accountIds());
        return ConsentView.of(consent);
    }

    @PostMapping("/{consentId}/authorize")
    public ConsentView authorize(@PathVariable String consentId, HttpServletRequest httpRequest) {
        return ConsentView.of(consentService.authorize(principal(httpRequest).partnerId(), consentId));
    }

    @PostMapping("/{consentId}/suspend")
    public ConsentView suspend(@PathVariable String consentId, HttpServletRequest httpRequest) {
        return ConsentView.of(consentService.suspend(principal(httpRequest).partnerId(), consentId));
    }

    @PostMapping("/{consentId}/resume")
    public ConsentView resume(@PathVariable String consentId, HttpServletRequest httpRequest) {
        return ConsentView.of(consentService.resume(principal(httpRequest).partnerId(), consentId));
    }

    @PostMapping("/{consentId}/revoke")
    public ConsentView revoke(@PathVariable String consentId, HttpServletRequest httpRequest) {
        return ConsentView.of(consentService.revoke(principal(httpRequest).partnerId(), consentId));
    }

    @GetMapping("/{consentId}")
    public ConsentView get(@PathVariable String consentId, HttpServletRequest httpRequest) {
        return ConsentView.of(consentService.get(principal(httpRequest).partnerId(), consentId));
    }

    private PartnerPrincipal principal(HttpServletRequest request) {
        PartnerPrincipal principal = SecuritySupport.principal(request);
        SecuritySupport.requireScope(principal, SCOPE_CONSENTS);
        return principal;
    }
}
