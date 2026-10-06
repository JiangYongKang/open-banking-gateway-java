package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;
import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败提交的回查：业务拒绝（余额不足/授权不可用/限流）落幂等记录，
 * 同键同报文原样回放当时判定（不重新执行），同键异报文明确冲突。
 * 保留期清理只摘除回查入口，不影响已生效支付与账务。
 */
class FailedPaymentLookupTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));
    private static final CallerContext BETA = new CallerContext("partner-beta",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][LOOKUP] " + fmt.formatted(args));
    }

    private String authorizedConsent(TestFixtures.Harness h) {
        h.accountRepository().save(new Account("acct-1001", "Alice", new BigDecimal("100.00")));
        h.accountRepository().save(new Account("acct-1002", "Bob", new BigDecimal("0.00")));
        ConsentRequest req = new ConsentRequest();
        req.setAccountIds(Set.of("acct-1001", "acct-1002"));
        req.setScopes(Set.of(Scope.PAYMENTS_INITIATE));
        Consent c = h.consentService().create(ALPHA, req);
        h.consentService().authorize(ALPHA, c.id());
        return c.id();
    }

    private PaymentRequest payment(String consentId, String from, String to, String amount) {
        PaymentRequest r = new PaymentRequest();
        r.setConsentId(consentId);
        r.setFromAccountId(from);
        r.setToAccountId(to);
        r.setAmount(new BigDecimal(amount));
        r.setCurrency("CNY");
        return r;
    }

    @Test
    void insufficientFundsIsRecordedAndReplayedWithoutReexecution() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        PaymentRequest req = payment(consentId, "acct-1001", "acct-1002", "500.00");

        GatewayException first = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-funds-1", req));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.errorCode());
        say("idempotencyKey=lk-funds-1 reject=%s basis=balance 100.00 < amount 500.00",
                first.errorCode().code());

        // 回查：可区分的失败原因原样可见
        IdempotencyRecord lookedUp = h.paymentService().lookup(ALPHA, "lk-funds-1");
        assertEquals(IdempotencyRecord.Outcome.REJECTED, lookedUp.outcome());
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, lookedUp.rejectReason());
        say("lookup lk-funds-1 -> outcome=REJECTED reason=%s basis=recorded-first-decision",
                lookedUp.rejectReason().code());

        // 充值后同键同报文重提：仍回放首次失败，不重新执行（余额不动）
        h.accountRepository().adjustBalance("acct-1001", new BigDecimal("10000.00"));
        GatewayException replayed = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-funds-1", req));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, replayed.errorCode());
        assertEquals(first.getMessage(), replayed.getMessage(), "必须原样返回首次判定");
        assertEquals(0, h.paymentRepository().findAll().size(), "回放失败不得重新执行");
        assertEquals(0, new BigDecimal("10100.00").compareTo(
                h.accountRepository().findById("acct-1001").orElseThrow().balance()));
        say("resubmit lk-funds-1 same-payload after top-up -> replayed %s, payments=%d basis=no-reexecution",
                replayed.errorCode().code(), h.paymentRepository().findAll().size());
    }

    @Test
    void sameKeyDifferentPayloadAfterFailureConflicts() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);

        GatewayException first = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-conflict-1",
                        payment(consentId, "acct-1001", "acct-1002", "500.00")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.errorCode());

        // 首次失败后，同键异报文必须明确冲突，不能靠改报文绕过失败
        GatewayException conflict = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-conflict-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.errorCode());
        assertEquals(0, h.paymentRepository().findAll().size(), "冲突请求不得生效");
        assertEquals(0, new BigDecimal("100.00").compareTo(
                h.accountRepository().findById("acct-1001").orElseThrow().balance()));
        say("idempotencyKey=lk-conflict-1 first=INSUFFICIENT_FUNDS then different-payload -> %s basis=fingerprint-differs",
                conflict.errorCode().code());
    }

    @Test
    void revokedConsentAndRateLimitRejectionsAreQueryable() {
        GatewayProperties props = new GatewayProperties();
        props.getRateLimit().setRequestsPerWindow(1);
        props.getRateLimit().setWindow(Duration.ofMinutes(5));
        var h = TestFixtures.newHarness(props);
        String consentId = authorizedConsent(h);

        // 授权不可用：先撤销，再支付
        h.consentService().revoke(ALPHA, consentId);
        GatewayException revoked = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-revoked-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        assertEquals(ErrorCode.CONSENT_REVOKED, revoked.errorCode());
        IdempotencyRecord revokedRecord = h.paymentService().lookup(ALPHA, "lk-revoked-1");
        assertEquals(ErrorCode.CONSENT_REVOKED, revokedRecord.rejectReason());
        say("lookup lk-revoked-1 -> REJECTED reason=%s basis=consent-revoked-before-submit",
                revokedRecord.rejectReason().code());

        // 限流：窗口 1 次已被上面的提交消耗，下一键触发限流
        GatewayException limited = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-ratelimit-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode());
        IdempotencyRecord limitedRecord = h.paymentService().lookup(ALPHA, "lk-ratelimit-1");
        assertEquals(ErrorCode.RATE_LIMITED, limitedRecord.rejectReason());
        say("lookup lk-ratelimit-1 -> REJECTED reason=%s basis=window-exhausted",
                limitedRecord.rejectReason().code());
    }

    @Test
    void successLookupReturnsPaymentAndUnknownOrCrossPartnerKeyIs404() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        Payment ok = h.paymentService().submit(ALPHA, "lk-ok-1",
                payment(consentId, "acct-1001", "acct-1002", "10.00"));

        IdempotencyRecord record = h.paymentService().lookup(ALPHA, "lk-ok-1");
        assertEquals(IdempotencyRecord.Outcome.COMPLETED, record.outcome());
        assertEquals(ok.id(), record.payment().id());
        say("lookup lk-ok-1 -> COMPLETED paymentId=%s basis=stored-success", ok.id());

        GatewayException unknown = assertThrows(GatewayException.class,
                () -> h.paymentService().lookup(ALPHA, "lk-never-seen"));
        assertEquals(ErrorCode.NOT_FOUND, unknown.errorCode());
        GatewayException crossPartner = assertThrows(GatewayException.class,
                () -> h.paymentService().lookup(BETA, "lk-ok-1"));
        assertEquals(ErrorCode.NOT_FOUND, crossPartner.errorCode(),
                "他人键与不存在键对外同构");
        say("lookup unknown-key -> %s; cross-partner lk-ok-1 -> %s basis=key-scoped-by-partner",
                unknown.errorCode().code(), crossPartner.errorCode().code());
    }

    @Test
    void purgeRemovesOnlyExpiredRecordsAndNeverTouchesPaymentsOrLedger() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        Payment ok = h.paymentService().submit(ALPHA, "lk-purge-ok",
                payment(consentId, "acct-1001", "acct-1002", "10.00"));
        assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-purge-fail",
                        payment(consentId, "acct-1001", "acct-1002", "99999.00")));
        assertEquals(2, h.idempotencyStore().size());

        // 窗口内清理：cutoff 取过去，什么都不动
        int purgedNone = h.idempotencyStore().purgeExpired(Instant.now().minus(Duration.ofHours(1)));
        assertEquals(0, purgedNone);
        assertEquals(2, h.idempotencyStore().size());
        say("purge cutoff=now-1h -> purged=%d basis=all-records-in-window", purgedNone);

        // 超窗清理：cutoff 取未来，两条回查记录都摘除
        int purged = h.idempotencyStore().purgeExpired(Instant.now().plus(Duration.ofHours(1)));
        assertEquals(2, purged);
        GatewayException gone = assertThrows(GatewayException.class,
                () -> h.paymentService().lookup(ALPHA, "lk-purge-ok"));
        assertEquals(ErrorCode.NOT_FOUND, gone.errorCode());
        say("purge cutoff=now+1h -> purged=%d; lookup lk-purge-ok -> %s basis=retention-window-exceeded",
                purged, gone.errorCode().code());

        // 已生效支付与账务不受清理影响
        assertEquals(1, h.paymentRepository().findAll().size());
        assertEquals(ok.id(), h.paymentRepository().findAll().get(0).id());
        assertEquals(2, h.ledgerRepository().findAll().size());
        assertEquals(0, h.ledgerRepository().signedTotal().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("90.00").compareTo(
                h.accountRepository().findById("acct-1001").orElseThrow().balance()));
        assertEquals(0, new BigDecimal("10.00").compareTo(
                h.accountRepository().findById("acct-1002").orElseThrow().balance()));
        say("post-purge payments=%d ledgerEntries=%d ledgerNet=%s basis=purge-never-touches-ledger",
                h.paymentRepository().findAll().size(), h.ledgerRepository().findAll().size(),
                h.ledgerRepository().signedTotal());
        assertTrue(h.paymentRepository().findAll().get(0).amount().compareTo(new BigDecimal("10.00")) == 0);
    }
}
