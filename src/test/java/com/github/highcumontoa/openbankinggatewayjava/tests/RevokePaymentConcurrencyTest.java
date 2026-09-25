package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.account.Account;
import com.github.highcumontoa.openbankinggatewayjava.account.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.consent.Consent;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentRepository;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.ledger.LedgerService;
import com.github.highcumontoa.openbankinggatewayjava.payment.IdempotencyStore;
import com.github.highcumontoa.openbankinggatewayjava.payment.PaymentService;
import com.github.highcumontoa.openbankinggatewayjava.payment.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * “撤销与支付同时发生”并发测试：
 * 结果只能是“支付完整生效”或“以 CONSENT_REVOKED 被拒”，不得半生效；
 * 每笔成功支付恰好两条配对账目，失败支付不留任何账目；全量账目净额恒为 0。
 */
class RevokePaymentConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RevokePaymentConcurrencyTest.class);

    private static final int PAYERS = 12;

    static final class FixedClock extends Clock {
        private final Instant instant = Instant.parse("2026-09-25T00:00:00Z");

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

    @Test
    @DisplayName("撤销与支付并发：无半生效，原因可区分，成功笔数与账目一致且守恒")
    void revokeRacesPayments() throws Exception {
        AtomicInteger acceptedTotal = new AtomicInteger();
        AtomicInteger revokedRejectedTotal = new AtomicInteger();
        boolean sawBothOutcomesAcrossRuns = false;

        int iterations = 40;
        for (int iter0 = 0; iter0 < iterations; iter0++) {
            final int iter = iter0;
            Context ctx = newContext();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(PAYERS + 1);

            List<Future<?>> futures = new ArrayList<>();
            // 多个支付方（各自独立幂等键），与一个撤销方在同一栅栏同时起跑
            for (int p = 0; p < PAYERS; p++) {
                final int idx = p;
                futures.add(pool.submit(() -> {
                    start.await();
                    String key = "iter-" + iter + "-payer-" + idx;
                    try {
                        ctx.paymentService.submit("partner-a", key, ctx.consentId, "ACCT-001",
                                "Shop", "EXT-9", new BigDecimal("1.00"), "EUR");
                        ctx.accepted.incrementAndGet();
                    } catch (GatewayException e) {
                        if (e.errorCode() == ErrorCode.CONSENT_REVOKED) {
                            ctx.revokedRejected.incrementAndGet();
                        } else {
                            ctx.unexpected.incrementAndGet();
                            log.error("[TEST] unexpected errorCode={}", e.errorCode());
                        }
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                ctx.consentService.revoke("partner-a", ctx.consentId);
                return null;
            }));

            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            pool.shutdown();

            int accepted = ctx.accepted.get();
            int rejected = ctx.revokedRejected.get();
            acceptedTotal.addAndGet(accepted);
            revokedRejectedTotal.addAndGet(rejected);
            if (accepted > 0 && rejected > 0) {
                sawBothOutcomesAcrossRuns = true;
            }

            // 每次迭代的核心不变量
            assertThat(accepted + rejected).isEqualTo(PAYERS);
            assertThat(ctx.unexpected.get()).isZero();
            assertThat(ctx.consentService.get("partner-a", ctx.consentId).getStatus())
                    .isEqualTo(ConsentStatus.REVOKED);
            // 成功笔数 * 2 条账目；全量净额恒为 0（金额守恒，无半生效）
            assertThat(ctx.ledgerService.entries()).hasSize(accepted * 2);
            assertThat(ctx.ledgerService.totalNet()).isEqualByComparingTo("0");
            // 余额：初始 100000，仅成功的支付各扣 1
            assertThat(ctx.balance()).isEqualByComparingTo(
                    new BigDecimal("100000").subtract(new BigDecimal(accepted)));

            if (iter < 3 || (accepted > 0 && rejected > 0)) {
                log.info("[TEST] revoke-vs-pay | iter={} | idempotencyKeys=iter-{}-payer-* | transition=AUTHORISED -> REVOKED | accepted={} revokedRejected={} | judge=serialized no-half-effect",
                        iter, iter, accepted, rejected);
            }
        }

        log.info("[TEST] revoke-vs-pay summary | iterations={} | acceptedTotal={} revokedRejectedTotal={} | sawBothOutcomes={}",
                iterations, acceptedTotal.get(), revokedRejectedTotal.get(), sawBothOutcomesAcrossRuns);
        // 40 轮高并发下，两种结局（撤销前成功 / 撤销后被拒）必然都出现过，证明串行化双向都被覆盖
        assertThat(sawBothOutcomesAcrossRuns)
                .as("并发交叉应同时观察到成功与撤销拒绝两种结果")
                .isTrue();
        assertThat(acceptedTotal.get() + revokedRejectedTotal.get())
                .isEqualTo(iterations * PAYERS);
    }

    private static final class Context {
        AccountRepository accountRepository;
        LedgerService ledgerService;
        ConsentService consentService;
        PaymentService paymentService;
        String consentId;
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger revokedRejected = new AtomicInteger();
        final AtomicInteger unexpected = new AtomicInteger();

        BigDecimal balance() {
            return accountRepository.findById("ACCT-001").orElseThrow().getBalance();
        }
    }

    private Context newContext() {
        Clock clock = new FixedClock();
        GatewayProperties properties = new GatewayProperties();
        properties.setConsentTtlSeconds(3600L);
        properties.setRateLimitPermitsPerMinute(100000);
        properties.setRateLimitBurstCapacity(100000);

        Context ctx = new Context();
        ctx.accountRepository = new AccountRepository();
        ctx.accountRepository.save(new Account("ACCT-001", "EUR", new BigDecimal("100000")));
        ctx.accountRepository.save(new Account(LedgerService.clearingAccount("EUR"), "EUR", BigDecimal.ZERO));

        ctx.ledgerService = new LedgerService(ctx.accountRepository, clock);
        ctx.consentService = new ConsentService(new ConsentRepository(), properties, clock);
        Consent consent = ctx.consentService.create("partner-a",
                java.util.Set.of("payments", "accounts.read"), java.util.Set.of("ACCT-001"));
        ctx.consentService.authorize("partner-a", consent.getId());
        ctx.consentId = consent.getId();

        ctx.paymentService = new PaymentService(new IdempotencyStore(),
                new RateLimiter(properties, clock), ctx.consentService, ctx.ledgerService, clock);
        return ctx;
    }
}
