package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentSnapshot;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本与一致性语义：
 * 每次成功迁移 version 单调递增、updatedAt 只增不减；
 * 读取与撤销并发时快照不撕裂（二选一且可解释）；
 * 两个变更并发时一个成功一个冲突，不互相覆盖。
 */
class ConsentVersionConcurrencyTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][VERSION] " + fmt.formatted(args));
    }

    private Consent newConsent(TestFixtures.Harness h) {
        ConsentRequest req = new ConsentRequest();
        req.setAccountIds(Set.of("acct-1001"));
        req.setScopes(Set.of(Scope.PAYMENTS_INITIATE, Scope.ACCOUNTS_READ));
        return h.consentService().create(ALPHA, req);
    }

    @Test
    void versionIncrementsAndUpdatedAtNeverGoesBackwards() {
        var h = TestFixtures.newHarness();
        Consent c = newConsent(h);
        assertEquals(0, c.version());

        h.consentService().authorize(ALPHA, c.id());
        assertEquals(1, c.version());
        Instant t1 = c.updatedAt();

        h.consentService().suspend(ALPHA, c.id());
        assertEquals(2, c.version());
        assertFalse(c.updatedAt().isBefore(t1), "updatedAt 只增不减");
        Instant t2 = c.updatedAt();

        h.consentService().resume(ALPHA, c.id());
        h.consentService().revoke(ALPHA, c.id());
        assertEquals(4, c.version());
        assertFalse(c.updatedAt().isBefore(t2));

        // 失败的迁移不推进版本
        assertThrows(GatewayException.class, () -> h.consentService().resume(ALPHA, c.id()));
        assertEquals(4, c.version(), "被拒绝的迁移不得推进版本");
        say("consentId=%s transitions=authorize/suspend/resume/revoke version=%d "
                        + "rejected-resume-keeps-version basis=markTransition-only-on-success",
                c.id(), c.version());
    }

    @Test
    void concurrentReadsDuringRevokeNeverSeeTornState() throws Exception {
        var h = TestFixtures.newHarness();
        int rounds = 30;
        AtomicInteger torn = new AtomicInteger();
        AtomicInteger sawBefore = new AtomicInteger();
        AtomicInteger sawAfter = new AtomicInteger();

        for (int round = 0; round < rounds; round++) {
            Consent c = newConsent(h);
            h.consentService().authorize(ALPHA, c.id());
            long preVersion = c.version();

            ExecutorService pool = Executors.newFixedThreadPool(5);
            CountDownLatch start = new CountDownLatch(1);
            List<ConsentSnapshot> snapshots = new CopyOnWriteArrayList<>();
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 50; j++) {
                            snapshots.add(h.consentService().snapshot(c));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    start.await();
                    h.consentService().revoke(ALPHA, c.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            for (ConsentSnapshot s : snapshots) {
                boolean before = s.status() == ConsentStatus.AUTHORIZED && s.version() == preVersion && s.usable();
                boolean after = s.status() == ConsentStatus.REVOKED && s.version() == preVersion + 1 && !s.usable();
                if (!before && !after) {
                    torn.incrementAndGet();
                    say("TORN consentId=%s status=%s version=%d usable=%s",
                            c.id(), s.status(), s.version(), s.usable());
                }
                if (before) {
                    sawBefore.incrementAndGet();
                }
                if (after) {
                    sawAfter.incrementAndGet();
                }
            }
        }
        say("rounds=%d torn=%d sawBefore=%d sawAfter=%d basis=snapshot-under-consent-lock",
                rounds, torn.get(), sawBefore.get(), sawAfter.get());
        assertEquals(0, torn.get(), "快照只能是撤销前或撤销后的完整状态");
        assertTrue(sawBefore.get() > 0 && sawAfter.get() > 0, "两种结局都应被观察到");
    }

    @Test
    void concurrentTransitionsConflictInsteadOfOverwriting() throws Exception {
        var h = TestFixtures.newHarness();
        int rounds = 30;
        int bothSucceeded = 0;

        for (int round = 0; round < rounds; round++) {
            Consent c = newConsent(h);
            h.consentService().authorize(ALPHA, c.id());
            long preVersion = c.version();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger conflicted = new AtomicInteger();
            Runnable suspend = () -> {
                try {
                    start.await();
                    h.consentService().suspend(ALPHA, c.id());
                    succeeded.incrementAndGet();
                } catch (GatewayException ex) {
                    if (ex.errorCode() == ErrorCode.INVALID_CONSENT_STATE
                            || ex.errorCode() == ErrorCode.CONSENT_REVOKED) {
                        conflicted.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Runnable revoke = () -> {
                try {
                    start.await();
                    h.consentService().revoke(ALPHA, c.id());
                    succeeded.incrementAndGet();
                } catch (GatewayException ex) {
                    if (ex.errorCode() == ErrorCode.INVALID_CONSENT_STATE
                            || ex.errorCode() == ErrorCode.CONSENT_REVOKED) {
                        conflicted.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            pool.submit(suspend);
            pool.submit(revoke);
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            // 两个并发变更：可能都成功（revoke 后 suspend 被拒；或 suspend 后 revoke 合法）
            // 关键不变量：版本数 == 成功迁移数，状态与版本自洽，绝不互相覆盖
            assertEquals(preVersion + succeeded.get(), c.version(),
                    "round " + round + " 版本必须精确等于成功迁移次数");
            ConsentSnapshot snap = h.consentService().snapshot(c);
            assertTrue(snap.status() == ConsentStatus.SUSPENDED || snap.status() == ConsentStatus.REVOKED,
                    "round " + round + " 终态必须是某个迁移目标");
            if (succeeded.get() == 2) {
                // suspend 先、revoke 后：终态必为 REVOKED
                assertEquals(ConsentStatus.REVOKED, snap.status());
                bothSucceeded++;
            } else {
                assertEquals(1, conflicted.get(), "round " + round + " 败者必须拿到冲突提示");
            }
        }
        say("rounds=%d bothSucceeded=%d basis=recheck-under-lock-loser-gets-conflict", rounds, bothSucceeded);
    }

    @Test
    void paymentDecisionFollowsLatestCommittedTransition() {
        GatewayProperties props = new GatewayProperties();
        props.getRateLimit().setRequestsPerWindow(1000);
        var h = TestFixtures.newHarness(props);
        h.accountRepository().save(new Account("acct-1001", "Alice", new BigDecimal("100.00")));
        h.accountRepository().save(new Account("acct-1002", "Bob", new BigDecimal("0.00")));
        ConsentRequest req = new ConsentRequest();
        req.setAccountIds(Set.of("acct-1001", "acct-1002"));
        req.setScopes(Set.of(Scope.PAYMENTS_INITIATE));
        Consent c = h.consentService().create(ALPHA, req);
        h.consentService().authorize(ALPHA, c.id());

        // 撤销返回后提交的支付必须按新状态拒绝（读不到旧状态）
        h.consentService().revoke(ALPHA, c.id());
        var payReq = new com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest();
        payReq.setConsentId(c.id());
        payReq.setFromAccountId("acct-1001");
        payReq.setToAccountId("acct-1002");
        payReq.setAmount(new BigDecimal("1.00"));
        payReq.setCurrency("CNY");
        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.paymentService().submit(ALPHA, "vk-after-revoke", payReq));
        assertEquals(ErrorCode.CONSENT_REVOKED, ex.errorCode());
        assertEquals(0, h.paymentRepository().findAll().size());
        say("idempotencyKey=vk-after-revoke reject=%s version=%d basis=decision-sees-latest-committed-state",
                ex.errorCode().code(), c.version());
    }
}
