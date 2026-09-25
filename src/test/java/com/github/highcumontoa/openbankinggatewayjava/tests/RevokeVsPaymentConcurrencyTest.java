package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销与支付并发：每个结局必须完整（支付全生效含两条平衡分录）或完全不发生，
 * 不允许只扣款、无分录等半生效状态；账本始终守恒。
 */
class RevokeVsPaymentConcurrencyTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][REVOKE-RACE] " + fmt.formatted(args));
    }

    @Test
    void revokeAndPaymentsRaceHasNoHalfEffect() throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getRateLimit().setRequestsPerWindow(1_000_000);
        var h = TestFixtures.newHarness(props);

        int rounds = 40;
        int payersPerRound = 8;
        int totalApplied = 0;
        int totalRejected = 0;
        boolean observedApplied = false;
        boolean observedRejected = false;

        for (int round = 0; round < rounds; round++) {
            String from = "acct-r" + round + "-1";
            String to = "acct-r" + round + "-2";
            h.accountRepository().save(new Account(from, "Alice", new BigDecimal("1000.00")));
            h.accountRepository().save(new Account(to, "Bob", new BigDecimal("0.00")));
            ConsentRequest cr = new ConsentRequest();
            cr.setAccountIds(Set.of(from, to));
            cr.setScopes(Set.of(Scope.PAYMENTS_INITIATE));
            Consent consent = h.consentService().create(ALPHA, cr);
            h.consentService().authorize(ALPHA, consent.id());

            final int rnd = round;
            ExecutorService pool = Executors.newFixedThreadPool(payersPerRound + 1);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger applied = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            long revokeDelayNanos = (round % 5) * 200_000L; // 交错撤销时机，制造双方都可能赢

            for (int i = 0; i < payersPerRound; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        start.await();
                        PaymentRequest r = new PaymentRequest();
                        r.setConsentId(consent.id());
                        r.setFromAccountId(from);
                        r.setToAccountId(to);
                        r.setAmount(new BigDecimal("1.00"));
                        r.setCurrency("CNY");
                        h.paymentService().submit(ALPHA, "race-" + rnd + "-" + idx, r);
                        applied.incrementAndGet();
                    } catch (GatewayException ex) {
                        if (ex.errorCode() == ErrorCode.CONSENT_REVOKED
                                || ex.errorCode() == ErrorCode.INVALID_CONSENT_STATE) {
                            rejected.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    start.await();
                    if (revokeDelayNanos > 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(revokeDelayNanos);
                    }
                    h.consentService().revoke(ALPHA, consent.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(payersPerRound, applied.get() + rejected.get(),
                    "round " + rnd + " 每个支付线程必须有明确结局");
            assertEquals(applied.get(), h.paymentRepository().findAll().stream()
                    .filter(p -> p.consentId().equals(consent.id())).count(), "round " + rnd);
            // 余额与生效笔数精确吻合
            assertEquals(0, new BigDecimal(1000 - applied.get()).compareTo(
                    h.accountRepository().findById(from).orElseThrow().balance()), "round " + rnd);
            assertEquals(0, new BigDecimal(applied.get()).compareTo(
                    h.accountRepository().findById(to).orElseThrow().balance()), "round " + rnd);

            totalApplied += applied.get();
            totalRejected += rejected.get();
            observedApplied |= applied.get() > 0;
            observedRejected |= rejected.get() > 0;
        }

        // 全局不变量：账本守恒、分录平衡、撤销后状态正确
        BigDecimal expectedTotal = new BigDecimal(rounds * 1000L + ".00");
        assertEquals(0, expectedTotal.compareTo(h.accountRepository().totalBalance()));
        assertEquals(0, h.ledgerRepository().signedTotal().compareTo(BigDecimal.ZERO));
        assertEquals(h.paymentRepository().findAll().size() * 2L, h.ledgerRepository().findAll().size());

        say("rounds=%d payersPerRound=%d totalApplied=%d totalRejected=%d observedApplied=%s observedRejected=%s",
                rounds, payersPerRound, totalApplied, totalRejected, observedApplied, observedRejected);
        say("totalBalance=%s ledgerNet=%s basis=consent-lock-mutual-exclusion-no-half-effect",
                h.accountRepository().totalBalance(), h.ledgerRepository().signedTotal());
        assertTrue(observedApplied, "至少一轮存在支付先生效");
        assertTrue(observedRejected, "至少一轮存在撤销先于部分支付");
    }

}
