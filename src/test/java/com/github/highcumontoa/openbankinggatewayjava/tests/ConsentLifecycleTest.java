package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 同意书状态机：合法迁移、过期/撤销/范围不符/越权的可区分拒绝原因。
 */
class ConsentLifecycleTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));
    private static final CallerContext BETA = new CallerContext("partner-beta",
            Set.of(Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][CONSENT] " + fmt.formatted(args));
    }

    @Test
    void legalLifecycle() {
        var h = TestFixtures.newHarness();
        Consent c = h.consentService().create(ALPHA, request(Set.of("acct-1001"),
                Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE)));
        say("created consentId=%s status=%s basis=initial-state", c.id(), c.status());
        assertEquals(ConsentStatus.CREATED, c.status());

        c = h.consentService().authorize(ALPHA, c.id());
        say("transition consentId=%s -> %s basis=CREATED may authorize", c.id(), c.status());
        c = h.consentService().suspend(ALPHA, c.id());
        say("transition consentId=%s -> %s basis=AUTHORIZED may suspend", c.id(), c.status());
        c = h.consentService().resume(ALPHA, c.id());
        say("transition consentId=%s -> %s basis=SUSPENDED may resume", c.id(), c.status());
        c = h.consentService().revoke(ALPHA, c.id());
        say("transition consentId=%s -> %s basis=AUTHORIZED may revoke", c.id(), c.status());
        assertEquals(ConsentStatus.REVOKED, c.status());
    }

    @Test
    void authorizeFromCreatedOnly_andRejectIllegalTransitions() {
        var h = TestFixtures.newHarness();
        Consent c = h.consentService().create(ALPHA, request(Set.of("acct-1001"),
                Set.of(Scope.PAYMENTS_INITIATE)));

        assertCode(ErrorCode.INVALID_CONSENT_STATE,
                () -> h.consentService().suspend(ALPHA, c.id()));
        say("consentId=%s action=suspend-from-CREATED reject=%s basis=only-AUTHORIZED-suspendable",
                c.id(), ErrorCode.INVALID_CONSENT_STATE.code());

        h.consentService().authorize(ALPHA, c.id());
        h.consentService().revoke(ALPHA, c.id());
        assertCode(ErrorCode.CONSENT_REVOKED, () -> h.consentService().authorize(ALPHA, c.id()));
        assertCode(ErrorCode.CONSENT_REVOKED, () -> h.consentService().resume(ALPHA, c.id()));
        say("consentId=%s actions=authorize/resume-after-revoke reject=%s basis=REVOKED-terminal",
                c.id(), ErrorCode.CONSENT_REVOKED.code());
    }

    @Test
    void expiredConsentCannotTransitionOrBeUsed() {
        var h = TestFixtures.newHarness();
        Consent expired = new Consent("consent-expired", "partner-alpha",
                Set.of("acct-1001"), Set.of(Scope.ACCOUNTS_READ),
                Instant.now().minusSeconds(3600), Instant.now().minusSeconds(10),
                ConsentStatus.AUTHORIZED);
        h.consentRepository().save(expired);

        assertCode(ErrorCode.CONSENT_EXPIRED,
                () -> h.consentService().suspend(ALPHA, expired.id()));
        say("consentId=%s action=suspend reject=%s basis=expiresAt<%s",
                expired.id(), ErrorCode.CONSENT_EXPIRED.code(), Instant.now());
        assertCode(ErrorCode.CONSENT_EXPIRED,
                () -> h.consentService().requireUsable(ALPHA, expired.id(), Scope.ACCOUNTS_READ));
    }

    @Test
    void crossPartnerAccessIsIndistinguishableFromNotFound() {
        var h = TestFixtures.newHarness();
        Consent c = h.consentService().create(ALPHA, request(Set.of("acct-1001"),
                Set.of(Scope.ACCOUNTS_READ)));

        GatewayException cross = assertCode(ErrorCode.NOT_FOUND,
                () -> h.consentService().getOwned(BETA, c.id()));
        GatewayException missing = assertCode(ErrorCode.NOT_FOUND,
                () -> h.consentService().getOwned(BETA, "consent-does-not-exist"));
        say("cross-partner consentId=%s reject=%s msg='%s'; absent reject=%s msg='%s' basis=identical-shape",
                c.id(), ErrorCode.NOT_FOUND.code(), cross.getMessage(),
                ErrorCode.NOT_FOUND.code(), missing.getMessage());
        assertEquals(cross.getMessage(), missing.getMessage().replace("consent-does-not-exist", c.id()));
    }

    @Test
    void scopeMismatchIsRejectedWithDistinctCode() {
        var h = TestFixtures.newHarness();
        Consent readOnly = h.consentService().create(ALPHA, request(Set.of("acct-1001"),
                Set.of(Scope.ACCOUNTS_READ)));
        h.consentService().authorize(ALPHA, readOnly.id());

        assertCode(ErrorCode.CONSENT_SCOPE_MISMATCH,
                () -> h.consentService().requireUsable(ALPHA, readOnly.id(), Scope.PAYMENTS_INITIATE));
        say("consentId=%s require=PAYMENTS_INITIATE granted=ACCOUNTS_READ reject=%s basis=scope-not-contained",
                readOnly.id(), ErrorCode.CONSENT_SCOPE_MISMATCH.code());

        CallerContext readOnlyToken = new CallerContext("partner-gamma", Set.of(Scope.ACCOUNTS_READ));
        assertCode(ErrorCode.INSUFFICIENT_SCOPE,
                () -> h.consentService().create(readOnlyToken,
                        request(Set.of("acct-1001"), Set.of(Scope.PAYMENTS_INITIATE))));
        say("create requested=PAYMENTS_INITIATE token=ACCOUNTS_READ reject=%s basis=consent-subset-of-token",
                ErrorCode.INSUFFICIENT_SCOPE.code());
    }

    @Test
    void accountOutsideConsentIsRejected() {
        var h = TestFixtures.newHarness();
        Consent c = h.consentService().create(ALPHA, request(Set.of("acct-1001"),
                Set.of(Scope.ACCOUNTS_READ)));
        h.consentService().authorize(ALPHA, c.id());
        assertCode(ErrorCode.ACCOUNT_NOT_IN_CONSENT,
                () -> h.consentService().requireAccountInConsent(c, "acct-9999"));
        say("consentId=%s account=acct-9999 reject=%s basis=not-in-authorized-set",
                c.id(), ErrorCode.ACCOUNT_NOT_IN_CONSENT.code());
    }

    private ConsentRequest request(Set<String> accounts, Set<Scope> scopes) {
        ConsentRequest r = new ConsentRequest();
        r.setAccountIds(accounts);
        r.setScopes(scopes);
        return r;
    }

    private GatewayException assertCode(ErrorCode expected, Runnable action) {
        GatewayException ex = assertThrows(GatewayException.class, action::run);
        assertEquals(expected, ex.errorCode());
        return ex;
    }
}
