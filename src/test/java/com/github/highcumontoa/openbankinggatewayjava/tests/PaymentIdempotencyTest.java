package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 幂等与限流：同键同报文生效一次；同键异报文冲突；各类失败不动余额。
 */
class PaymentIdempotencyTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][PAYMENT] " + fmt.formatted(args));
    }

    private GatewayProperties rateLimitedProperties() {
        GatewayProperties p = new GatewayProperties();
        p.getRateLimit().setRequestsPerWindow(2);
        p.getRateLimit().setWindow(Duration.ofMinutes(1));
        return p;
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
    void sameKeySamePayloadAppliesOnceAndReplays() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        PaymentRequest req = payment(consentId, "acct-1001", "acct-1002", "100.00");

        Payment first = h.paymentService().submit(ALPHA, "key-replay-1", req);
        Payment second = h.paymentService().submit(ALPHA, "key-replay-1", req);
        say("idempotencyKey=key-replay-1 first=%s second=%s basis=same-key-same-payload-replay",
                first.id(), second.id());

        assertSame(first, second, "重复提交必须回放同一支付实例");
        assertEquals(1, h.paymentRepository().findAll().size());
        assertEquals(2, h.ledgerRepository().findAll().size());
        assertEquals(new BigDecimal("900.00"), h.accountRepository().findById("acct-1001").orElseThrow().balance());
        assertEquals(new BigDecimal("100.00"), h.accountRepository().findById("acct-1002").orElseThrow().balance());
    }

    @Test
    void sameKeyDifferentPayloadConflictsAndAppliesNothingForSecond() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);

        Payment first = h.paymentService().submit(ALPHA, "key-conflict-1",
                payment(consentId, "acct-1001", "acct-1002", "10.00"));
        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "key-conflict-1",
                        payment(consentId, "acct-1001", "acct-1002", "99.00")));
        say("idempotencyKey=key-conflict-1 firstPayment=%s secondAmount=99 reject=%s basis=payload-fingerprint-differs",
                first.id(), ex.errorCode().code());

        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, ex.errorCode());
        assertEquals(1, h.paymentRepository().findAll().size(), "冲突请求不得再次生效");
        assertEquals(new BigDecimal("990.00"), h.accountRepository().findById("acct-1001").orElseThrow().balance());
    }

    @Test
    void rateLimitedRequestAppliesNothingButIdempotentReplayStillSucceeds() {
        var h = TestFixtures.newHarness(rateLimitedProperties());
        String consentId = authorizedConsent(h);

        Payment p1 = h.paymentService().submit(ALPHA, "rk-1",
                payment(consentId, "acct-1001", "acct-1002", "1.00"));
        h.paymentService().submit(ALPHA, "rk-2",
                payment(consentId, "acct-1001", "acct-1002", "1.00"));
        GatewayException limited = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "rk-3",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")));
        say("idempotencyKey=rk-3 reject=%s basis=2-per-window-exhausted; replay rk-1 -> %s",
                limited.errorCode().code(),
                h.paymentService().submit(ALPHA, "rk-1",
                        payment(consentId, "acct-1001", "acct-1002", "1.00")).id());

        assertEquals(ErrorCode.RATE_LIMITED, limited.errorCode());
        assertEquals(2, h.paymentRepository().findAll().size(), "限流请求不得生效");
        assertEquals(new BigDecimal("998.00"), h.accountRepository().findById("acct-1001").orElseThrow().balance());
    }

    @Test
    void validationAndFundsFailuresDoNotMoveBalance() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);

        GatewayException badAmount = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "vk-1",
                        payment(consentId, "acct-1001", "acct-1002", "-5.00")));
        assertEquals(ErrorCode.VALIDATION_ERROR, badAmount.errorCode());
        say("idempotencyKey=vk-1 reject=%s basis=amount-must-be-positive", badAmount.errorCode().code());

        GatewayException poor = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "vk-2",
                        payment(consentId, "acct-1001", "acct-1002", "1000000.00")));
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, poor.errorCode());
        say("idempotencyKey=vk-2 reject=%s basis=balance<amount; balance-unchanged", poor.errorCode().code());

        assertEquals(0, h.paymentRepository().findAll().size());
        assertEquals(new BigDecimal("1000.00"), h.accountRepository().findById("acct-1001").orElseThrow().balance());
        assertEquals(BigDecimal.ZERO, h.ledgerRepository().signedTotal());
    }

    @Test
    void missingIdempotencyKeyIsValidationError() {
        var h = TestFixtures.newHarness();
        String consentId = authorizedConsent(h);
        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, null, payment(consentId, "a", "b", "1.00")));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.errorCode());
        say("idempotencyKey=null reject=%s basis=header-required", ex.errorCode().code());
    }
}
