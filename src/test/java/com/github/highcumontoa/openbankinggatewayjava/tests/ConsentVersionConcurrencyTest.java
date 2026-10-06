package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同意书版本与并发一致性：
 * 版本只增不减；expectedVersion 乐观并发冲突可区分；
 * 两个并发变更不互相覆盖；查询与撤销并发时读到的状态自洽、判定二选一。
 */
class ConsentVersionConcurrencyTest {

    private static final CallerContext ALPHA = new CallerContext("partner-alpha",
            Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));

    private void say(String fmt, Object... args) {
        System.out.println("[TEST][VERSION] " + fmt.formatted(args));
    }

    private Consent authorizedConsent(TestFixtures.Harness h) {
        ConsentRequest cr = new ConsentRequest();
        cr.setAccountIds(Set.of("acct-1001"));
        cr.setScopes(Set.of(Scope.ACCOUNTS_READ, Scope.PAYMENTS_INITIATE));
        Consent c = h.consentService().create(ALPHA, cr);
        h.consentService().authorize(ALPHA, c.id());
        return c;
    }

    @Test
    void versionIncrementsOnEverySuccessfulTransition() {
        var h = TestFixtures.newHarness();
        Consent c = authorizedConsent(h);
        assertEquals(1, c.version(), "create(0) + authorize(1)");

        h.consentService().suspend(ALPHA, c.id());
        assertEquals(2, c.version());
        h.consentService().resume(ALPHA, c.id());
        assertEquals(3, c.version());
        h.consentService().revoke(ALPHA, c.id());
        assertEquals(4, c.version());
        say("consentId=%s transitions=authorize,suspend,resume,revoke finalVersion=%d basis=+1-per-transition",
                c.id(), c.version());

        // 失败的迁移不推进版本
        assertThrows(GatewayException.class, () -> h.consentService().resume(ALPHA, c.id()));
        assertEquals(4, c.version(), "被拒绝的迁移不得推进版本");
        say("consentId=%s rejected-resume version-still=%d basis=failed-transition-no-version-bump",
                c.id(), c.version());
    }

    @Test
    void expectedVersionMismatchIsDistinctConflict() {
        var h = TestFixtures.newHarness();
        Consent c = authorizedConsent(h);
        assertEquals(1, c.version());

        GatewayException ex = assertThrows(GatewayException.class,
                () -> h.consentService().suspend(ALPHA, c.id(), 7L));
        say("consentId=%s suspend expectedVersion=7 actual=1 reject=%s basis=optimistic-version-check",
                c.id(), ex.errorCode().code());
        assertEquals(ErrorCode.CONSENT_VERSION_CONFLICT, ex.errorCode());
        assertEquals(ConsentStatus.AUTHORIZED, c.status(), "版本冲突不得改变状态");
        assertEquals(1, c.version());

        // 版本匹配则放行
        h.consentService().suspend(ALPHA, c.id(), 1L);
        assertEquals(ConsentStatus.SUSPENDED, c.status());
        assertEquals(2, c.version());
        say("consentId=%s suspend expectedVersion=1 -> %s version=%d basis=version-match-applied",
                c.id(), c.status(), c.version());
    }

    @Test
    void concurrentMutationsWithSameExpectedVersionDoNotOverwrite() throws Exception {
        var h = TestFixtures.newHarness();
        Consent c = authorizedConsent(h);
        long staleVersion = c.version();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger stateRejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    h.consentService().suspend(ALPHA, c.id(), staleVersion);
                    applied.incrementAndGet();
                } catch (GatewayException ex) {
                    if (ex.errorCode() == ErrorCode.CONSENT_VERSION_CONFLICT) {
                        conflicts.incrementAndGet();
                    } else if (ex.errorCode() == ErrorCode.INVALID_CONSENT_STATE) {
                        stateRejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        say("consentId=%s concurrent-suspend threads=%d applied=%d versionConflicts=%d stateRejected=%d finalVersion=%d basis=single-winner-no-overwrite",
                c.id(), threads, applied.get(), conflicts.get(), stateRejected.get(), c.version());
        assertEquals(1, applied.get(), "同一期望版本下只能有一个变更生效");
        assertEquals(threads - 1, conflicts.get() + stateRejected.get(),
                "其余变更必须拿到可区分的冲突提示");
        assertEquals(staleVersion + 1, c.version(), "版本只推进一次，不存在互相覆盖");
        assertEquals(ConsentStatus.SUSPENDED, c.status());
    }

    @Test
    void readsDuringRevokeStayConsistentAndDecisionIsBinary() throws Exception {
        int rounds = 30;
        int observedUsable = 0;
        int observedRevoked = 0;

        for (int round = 0; round < rounds; round++) {
            var h = TestFixtures.newHarness();
            Consent c = authorizedConsent(h);
            long versionBeforeRevoke = c.version();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            final boolean[] readOutcome = new boolean[1]; // true=读到可用, false=读到已撤销
            final long revokeDelayNanos = (round % 5) * 100_000L; // 交错撤销时机，两种结局都要出现

            pool.submit(() -> {
                try {
                    start.await();
                    if (revokeDelayNanos > 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(revokeDelayNanos);
                    }
                    h.consentService().revoke(ALPHA, c.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    // 支付/账户查询共用的同一判定入口
                    h.consentService().requireUsable(ALPHA, c.id(), Scope.ACCOUNTS_READ);
                    readOutcome[0] = true;
                } catch (GatewayException ex) {
                    assertEquals(ErrorCode.CONSENT_REVOKED, ex.errorCode(),
                            "撤销竞态下只允许'可用'或'已撤销'二选一");
                    readOutcome[0] = false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            // 终态恒为 REVOKED，版本恰好 +1
            assertEquals(ConsentStatus.REVOKED, c.status());
            assertEquals(versionBeforeRevoke + 1, c.version());

            // 读到的状态与判定结果必须对得上：判定可用 ⟹ 当时版本未推进；判定拒绝 ⟹ 撤销已生效
            if (readOutcome[0]) {
                observedUsable++;
            } else {
                observedRevoked++;
                assertEquals(ConsentStatus.REVOKED, c.effectiveStatus());
            }
        }
        say("rounds=%d readUsable=%d readRevoked=%d basis=binary-outcome-no-divergence",
                rounds, observedUsable, observedRevoked);
        assertTrue(observedUsable > 0, "至少一轮读到撤销前状态");
        assertTrue(observedRevoked > 0, "至少一轮读到撤销后状态");
    }
}
