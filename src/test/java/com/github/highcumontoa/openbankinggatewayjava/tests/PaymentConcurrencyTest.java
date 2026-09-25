package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：同键重复提交只生效一次；多键并发转账金额守恒、不丢不重。
 */
class PaymentConcurrencyTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][CONCURRENCY] " + fmt.formatted(args));
    }

    private TestFixtures.Harness seeded(BigDecimal opening) {
        var h = TestFixtures.newHarness();
        h.accountRepository().save(new Account("acct-1001", "Alice", opening));
        h.accountRepository().save(new Account("acct-1002", "Bob", BigDecimal.ZERO));
        ConsentRequest cr = new ConsentRequest();
        cr.setAccountIds(Set.of("acct-1001", "acct-1002"));
        cr.setScopes(Set.of(Scope.PAYMENTS_INITIATE));
        Consent c = h.consentService().create(ALPHA, cr);
        h.consentService().authorize(ALPHA, c.id());
        return h;
    }

    private PaymentRequest req(String consentId, String amount) {
        PaymentRequest r = new PaymentRequest();
        r.setConsentId(consentId);
        r.setFromAccountId("acct-1001");
        r.setToAccountId("acct-1002");
        r.setAmount(new BigDecimal(amount));
        r.setCurrency("CNY");
        return r;
    }

    @Test
    void concurrentDuplicateSubmissionsApplyExactlyOnce() throws Exception {
        var h = seeded(new BigDecimal("1000.00"));
        String consentId = h.consentRepository().findAll().stream().findFirst().orElseThrow().id();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var p = h.paymentService().submit(ALPHA, "dup-key", req(consentId, "37.00"));
                    if (p.amount().equals(new BigDecimal("37.00"))) {
                        success.incrementAndGet();
                    }
                } catch (RuntimeException ex) {
                    // 不允许出现冲突类异常：同报文只能是回放成功
                    throw ex;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        // 每次 submit 都返回同一 payment（成功/回放不可从返回值区分，故以库中事实计数为准）
        assertEquals(1, h.paymentRepository().findAll().size(), "同键同报文并发只允许一笔支付落库");
        assertEquals(2, h.ledgerRepository().findAll().size(), "复式分录恰好两条");
        assertEquals(new BigDecimal("963.00"), h.accountRepository().findById("acct-1001").orElseThrow().balance());
        assertEquals(new BigDecimal("37.00"), h.accountRepository().findById("acct-1002").orElseThrow().balance());
        org.junit.jupiter.api.Assertions.assertEquals(0,
                h.ledgerRepository().signedTotal().compareTo(BigDecimal.ZERO), "分录有借必有贷，净额为零");
        say("idempotencyKey=dup-key threads=%d payments=%d ledgerEntries=%d basis=per-key-lock-serializes",
                threads, h.paymentRepository().findAll().size(), h.ledgerRepository().findAll().size());
    }

    @Test
    void concurrentDistinctPaymentsConserveTotal() throws Exception {
        var h = seededTwoWay(new BigDecimal("100000.00"));
        String consentId = h.consentRepository().findAll().stream().findFirst().orElseThrow().id();
        int count = 60;
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 半数 1001->1002，半数反向；账户同序加锁，不死锁
                    PaymentRequest r = idx % 2 == 0
                            ? req(consentId, "1.00")
                            : reverseReq(consentId, "1.00");
                    h.paymentService().submit(ALPHA, "distinct-key-" + idx, r);
                    done.incrementAndGet();
                } catch (RuntimeException ex) {
                    failed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        BigDecimal total = h.accountRepository().totalBalance();
        assertEquals(0, new BigDecimal("200000.00").compareTo(total), "两账户余额合计守恒: " + total);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                h.ledgerRepository().signedTotal().compareTo(BigDecimal.ZERO));
        assertEquals(count, h.paymentRepository().findAll().size(), "不同幂等键各生效一次");
        say("distinctKeys=%d applied=%d failed=%d totalBalance=%s ledgerNet=%s basis=ordered-account-locks",
                count, done.get(), failed.get(), total, h.ledgerRepository().signedTotal());
        assertEquals(0, failed.get());
    }

    private TestFixtures.Harness seededTwoWay(BigDecimal opening) {
        var props = new com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties();
        props.getRateLimit().setRequestsPerWindow(100_000);
        var h = TestFixtures.newHarness(props);
        h.accountRepository().save(new Account("acct-1001", "Alice", opening));
        h.accountRepository().save(new Account("acct-1002", "Bob", opening));
        ConsentRequest cr = new ConsentRequest();
        cr.setAccountIds(Set.of("acct-1001", "acct-1002"));
        cr.setScopes(Set.of(Scope.PAYMENTS_INITIATE));
        Consent c = h.consentService().create(ALPHA, cr);
        h.consentService().authorize(ALPHA, c.id());
        return h;
    }

    private PaymentRequest reverseReq(String consentId, String amount) {
        PaymentRequest r = new PaymentRequest();
        r.setConsentId(consentId);
        r.setFromAccountId("acct-1002");
        r.setToAccountId("acct-1001");
        r.setAmount(new BigDecimal(amount));
        r.setCurrency("CNY");
        return r;
    }
}
