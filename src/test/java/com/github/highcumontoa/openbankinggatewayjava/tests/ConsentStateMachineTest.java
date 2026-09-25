package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.consent.Consent;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentRepository;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同意书状态机单测：合法迁移、非法迁移原因可区分、过期懒迁移、跨合作方无差异拒绝。
 */
class ConsentStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(ConsentStateMachineTest.class);

    private MutableClock clock;
    private ConsentService service;

    static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-09-25T00:00:00Z");

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        GatewayProperties properties = new GatewayProperties();
        properties.setConsentTtlSeconds(3600L);
        service = new ConsentService(new ConsentRepository(), properties, clock);
    }

    private Consent newAuthorisedConsent(String partner, Set<String> scopes, Set<String> accounts) {
        Consent consent = service.create(partner, scopes, accounts);
        service.authorize(partner, consent.getId());
        return consent;
    }

    @Test
    @DisplayName("合法迁移链：创建->授权->暂停->恢复->撤销（终态）")
    void legalTransitions() {
        Consent consent = newAuthorisedConsent("partner-a",
                Set.of("accounts.read", "payments"), Set.of("ACCT-001"));
        String id = consent.getId();
        log.info("[TEST] state-machine | consentId={} | basis=legal chain start AUTHORISED", id);

        assertThat(service.suspend("partner-a", id).getStatus()).isEqualTo(ConsentStatus.SUSPENDED);
        assertThat(service.resume("partner-a", id).getStatus()).isEqualTo(ConsentStatus.AUTHORISED);
        assertThat(service.revoke("partner-a", id).getStatus()).isEqualTo(ConsentStatus.REVOKED);

        // 终态再迁移必须给出可区分原因（撤销幂等 vs 已撤销再操作）
        assertThat(service.revoke("partner-a", id).getStatus()).isEqualTo(ConsentStatus.REVOKED);
        assertThatThrownBy(() -> service.suspend("partner-a", id))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.CONSENT_REVOKED);
        log.info("[TEST] state-machine | consentId={} | transition=REVOKED -> suspend denied | judge=CONSENT_REVOKED", id);
    }

    @Test
    @DisplayName("非法迁移原因可区分：待授权不能暂停、暂停态不能授权、已撤销不能恢复")
    void illegalTransitionsAreDistinguishable() {
        Consent awaiting = service.create("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        assertCode(() -> service.suspend("partner-a", awaiting.getId()),
                ErrorCode.INVALID_CONSENT_TRANSITION);

        Consent authorised = newAuthorisedConsent("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        service.suspend("partner-a", authorised.getId());
        assertCode(() -> service.authorize("partner-a", authorised.getId()),
                ErrorCode.INVALID_CONSENT_TRANSITION);

        Consent other = newAuthorisedConsent("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        service.revoke("partner-a", other.getId());
        assertCode(() -> service.resume("partner-a", other.getId()), ErrorCode.CONSENT_REVOKED);
    }

    @Test
    @DisplayName("过期懒迁移：到达 expiresAt 后操作或使用均被以 CONSENT_EXPIRED 拒绝")
    void expiredConsentLazyTransition() {
        Consent consent = newAuthorisedConsent("partner-a",
                Set.of("payments"), Set.of("ACCT-001"));
        clock.advanceSeconds(3601L);
        log.info("[TEST] state-machine | consentId={} | basis=now past expiresAt, expect EXPIRED", consent.getId());

        assertCode(() -> service.suspend("partner-a", consent.getId()), ErrorCode.CONSENT_EXPIRED);
        assertThat(service.get("partner-a", consent.getId()).getStatus()).isEqualTo(ConsentStatus.EXPIRED);
        assertCode(() -> service.requireUsable("partner-a", consent.getId(), "payments", "ACCT-001"),
                ErrorCode.CONSENT_EXPIRED);
    }

    @Test
    @DisplayName("跨合作方访问与资源不存在返回完全相同的 RESOURCE_NOT_FOUND，不泄漏归属")
    void crossPartnerAccessIndistinguishableFromMissing() {
        Consent consent = newAuthorisedConsent("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));

        GatewayException cross = catchEx(() -> service.get("partner-b", consent.getId()));
        GatewayException missing = catchEx(() -> service.get("partner-b", "consent-does-not-exist"));
        assertThat(cross.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        log.info("[TEST] state-machine | judge=cross-partner vs missing both RESOURCE_NOT_FOUND | basis=no ownership leak");
    }

    @Test
    @DisplayName("授权范围不符：缺少 scope 与账户不在清单均以 CONSENT_SCOPE_MISMATCH 拒绝")
    void scopeAndAccountMismatch() {
        Consent consent = newAuthorisedConsent("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        assertCode(() -> service.requireUsable("partner-a", consent.getId(), "payments", "ACCT-001"),
                ErrorCode.CONSENT_SCOPE_MISMATCH);
        assertCode(() -> service.requireUsable("partner-a", consent.getId(), "accounts.read", "ACCT-999"),
                ErrorCode.CONSENT_SCOPE_MISMATCH);
    }

    @Test
    @DisplayName("暂停态使用同意书被以 CONSENT_SUSPENDED 拒绝，待授权态以 NOT_AUTHORIZED 拒绝")
    void suspendedAndAwaitingRejections() {
        Consent authorised = newAuthorisedConsent("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        service.suspend("partner-a", authorised.getId());
        assertCode(() -> service.requireUsable("partner-a", authorised.getId(), "accounts.read", "ACCT-001"),
                ErrorCode.CONSENT_SUSPENDED);

        Consent awaiting = service.create("partner-a",
                Set.of("accounts.read"), Set.of("ACCT-001"));
        assertCode(() -> service.requireUsable("partner-a", awaiting.getId(), "accounts.read", "ACCT-001"),
                ErrorCode.CONSENT_NOT_AUTHORIZED);
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(expected);
    }

    private GatewayException catchEx(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
            throw new AssertionError("expected GatewayException");
        } catch (GatewayException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }
}
