package com.github.highcumontoa.openbankinggatewayjava.tests;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等键回查：成功与业务拒绝都可查；同键同报文回放首次判定（不重新执行）；
 * 同键异报文判冲突；超保留窗口可清理且不影响已生效支付与账务。
 */
class PaymentLookupTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][LOOKUP] " + fmt.formatted(args));
    }

    private String authorizedConsent(TestFixtures.Harness h) {
        h.accountRepository().save(new Account("acct-1001", "Alice", new BigDecimal("1000.00")));
        h.accountRepository().save(new Account("acct-1002", "Bob", new BigDecimal("0.00")));
        var req = new com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest();
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
    void completedPaymentIsLookupableByKey() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        Payment p = h.paymentService().submit(ALPHA, "lk-ok-1",
                payment(consentId, "acct-1001", "acct-1002", "100.00"));

        IdempotencyRecord record = h.paymentService().lookup(ALPHA, "lk-ok-1");
        say("idempotencyKey=lk-ok-1 outcome=%s paymentId=%s basis=completed-record-stored",
                record.outcome(), record.payment().id());

        assertEquals(IdempotencyRecord.Outcome.COMPLETED, record.outcome());
        assertEquals(p.id(), record.payment().id());
        assertNull(record.rejectionCode());
    }

    @Test
    void insufficientFundsRejectionIsLookupableAndReplayedNotReexecuted() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        PaymentRequest req = payment(consentId, "acct-1001", "acct-1002", "1000000.00");

        GatewayException first = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-funds-1", req));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.errorCode());
        say("idempotencyKey=lk-funds-1 first-reject=%s basis=balance<amount", first.errorCode().code());

        // 回查：原样返回当时的可区分原因
        IdempotencyRecord record = h.paymentService().lookup(ALPHA, "lk-funds-1");
        say("idempotencyKey=lk-funds-1 lookup outcome=%s code=%s msg='%s' basis=rejection-recorded",
                record.outcome(), record.rejectionCode().code(), record.rejectionMessage());
        assertEquals(IdempotencyRecord.Outcome.REJECTED, record.outcome());
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, record.rejectionCode());
        assertEquals(first.getMessage(), record.rejectionMessage());

        // 补足余额后同键同报文重提：必须回放首次拒绝，不得重新执行
        h.accountRepository().adjustBalance("acct-1001", new BigDecimal("1000000.00"));
        GatewayException replayed = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-funds-1", req));
        say("idempotencyKey=lk-funds-1 resubmit-after-topup reject=%s basis=replay-rejection-no-reexecute",
                replayed.errorCode().code());
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, replayed.errorCode());
        assertEquals(0, h.paymentRepository().findAll().size(), "拒绝回放不得产生支付事实");
        // 只有手工补的 1000000，没有任何支付动账
        assertEquals(new BigDecimal("1001000.00"),
                h.accountRepository().findById("acct-1001").orElseThrow().balance());
    }

    @Test
    void consentStateRejectionIsLookupableWithDistinctReason() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        h.consentService().suspend(ALPHA, consentId);

        GatewayException first = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-consent-1",
                        payment(consentId, "acct-1001", "acct-1002", "10.00")));
        assertEquals(ErrorCode.INVALID_CONSENT_STATE, first.errorCode());

        IdempotencyRecord record = h.paymentService().lookup(ALPHA, "lk-consent-1");
        say("idempotencyKey=lk-consent-1 lookup outcome=%s code=%s basis=consent-suspended-at-submit",
                record.outcome(), record.rejectionCode().code());
        assertEquals(IdempotencyRecord.Outcome.REJECTED, record.outcome());
        assertEquals(ErrorCode.INVALID_CONSENT_STATE, record.rejectionCode());
    }

    @Test
    void rateLimitedRejectionIsLookupable() {
        GatewayProperties props = new GatewayProperties();
        props.getRateLimit().setRequestsPerWindow(1);
        props.getRateLimit().setWindow(Duration.ofMinutes(1));
        var h = TestFixtures.newHarness(props);
        String consentId = authorizedConsent(h);

        h.paymentService().submit(ALPHA, "lk-rl-0", payment(consentId, "acct-1001", "acct-1002", "1.00"));
        GatewayException limited = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-rl-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode());

        IdempotencyRecord record = h.paymentService().lookup(ALPHA, "lk-rl-1");
        say("idempotencyKey=lk-rl-1 lookup outcome=%s code=%s basis=rate-limit-recorded",
                record.outcome(), record.rejectionCode().code());
        assertEquals(IdempotencyRecord.Outcome.REJECTED, record.outcome());
        assertEquals(ErrorCode.RATE_LIMITED, record.rejectionCode());
    }

    @Test
    void sameKeyDifferentPayloadAfterFailureConflicts() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);

        GatewayException first = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-conflict-1",
                        payment(consentId, "acct-1001", "acct-1002", "1000000.00")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, first.errorCode());

        // 首次失败后同键换一份"能成功"的报文：必须冲突，不能靠改报文绕过失败
        GatewayException conflict = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-conflict-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        say("idempotencyKey=lk-conflict-1 firstReject=%s changedPayload reject=%s basis=fingerprint-differs",
                first.errorCode().code(), conflict.errorCode().code());
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.errorCode());
        assertEquals(0, h.paymentRepository().findAll().size(), "冲突请求不得生效");
        assertEquals(new BigDecimal("1000.00"),
                h.accountRepository().findById("acct-1001").orElseThrow().balance());
    }

    @Test
    void lookupOfUnknownKeyIsNotFound() {
        var h = TestFixtures.newHarness();
        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.paymentService().lookup(ALPHA, "lk-absent"));
        say("idempotencyKey=lk-absent lookup reject=%s basis=no-record", ex.errorCode().code());
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void expiredRecordsAreCleanedWithoutTouchingPaymentsOrLedger() {
        GatewayProperties props = new GatewayProperties();
        var h = TestFixtures.newHarness(props);
        String consentId = authorizedConsent(h);

        Payment ok = h.paymentService().submit(ALPHA, "lk-ret-ok",
                payment(consentId, "acct-1001", "acct-1002", "100.00"));
        GatewayException rejected = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "lk-ret-bad",
                        payment(consentId, "acct-1001", "acct-1002", "1000000.00")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, rejected.errorCode());

        // 保留窗口收窄到 0：两条记录都超窗，清理生效
        props.getIdempotency().setRetention(Duration.ZERO);
        int purged = h.idempotencyStore().purgeExpired(Instant.now());
        say("retention=0 purged=%d keys=[lk-ret-ok,lk-ret-bad] basis=createdAt+retention<now", purged);
        assertEquals(2, purged);

        GatewayException gone = assertThrows(GatewayException.class,
                () -> h.paymentService().lookup(ALPHA, "lk-ret-ok"));
        assertEquals(ErrorCode.NOT_FOUND, gone.errorCode());
        say("idempotencyKey=lk-ret-ok lookup-after-purge reject=%s basis=record-expired",
                gone.errorCode().code());

        // 清理不影响已生效支付、账本与余额
        assertEquals(1, h.paymentRepository().findAll().size());
        assertEquals(ok.id(), h.paymentRepository().findAll().get(0).id());
        assertEquals(2, h.ledgerRepository().findAll().size());
        assertEquals(0, h.ledgerRepository().signedTotal().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("900.00"),
                h.accountRepository().findById("acct-1001").orElseThrow().balance());
        assertEquals(new BigDecimal("100.00"),
                h.accountRepository().findById("acct-1002").orElseThrow().balance());
        say("post-purge payments=%d ledgerEntries=%d balance1001=%s basis=cleanup-only-affects-lookup",
                h.paymentRepository().findAll().size(), h.ledgerRepository().findAll().size(),
                h.accountRepository().findById("acct-1001").orElseThrow().balance());
    }
}
