package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.account.Account;
import com.github.highcumontoa.openbankinggatewayjava.account.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.consent.Consent;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentRepository;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.ledger.LedgerEntry;
import com.github.highcumontoa.openbankinggatewayjava.ledger.LedgerService;
import com.github.highcumontoa.openbankinggatewayjava.payment.IdempotencyRecord;
import com.github.highcumontoa.openbankinggatewayjava.payment.IdempotencyStore;
import com.github.highcumontoa.openbankinggatewayjava.payment.PaymentService;
import com.github.highcumontoa.openbankinggatewayjava.payment.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付服务单测：幂等只生效一次、报文冲突可区分、限流不扣余额、账本守恒、并发重复提交不重复生效。
 */
class PaymentIdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(PaymentIdempotencyTest.class);

    private Clock clock;
    private AccountRepository accountRepository;
    private LedgerService ledgerService;
    private ConsentService consentService;
    private RateLimiter rateLimiter;
    private PaymentService paymentService;
    private String consentId;

    @BeforeEach
    void setUp() {
        clock = new FixedClock(Instant.parse("2026-09-25T00:00:00Z"));
        GatewayProperties properties = new GatewayProperties();
        properties.setConsentTtlSeconds(3600L);
        properties.setRateLimitPermitsPerMinute(1000); // 单测默认放大限流，单独用例再覆盖
        properties.setRateLimitBurstCapacity(1000);

        accountRepository = new AccountRepository();
        accountRepository.save(new Account("ACCT-001", "EUR", new BigDecimal("1000.00")));
        accountRepository.save(new Account(LedgerService.clearingAccount("EUR"), "EUR", BigDecimal.ZERO));

        ledgerService = new LedgerService(accountRepository, clock);
        consentService = new ConsentService(new ConsentRepository(), properties, clock);
        Consent consent = consentService.create("partner-a",
                Set.of("accounts.read", "payments"), Set.of("ACCT-001"));
        consentService.authorize("partner-a", consent.getId());
        consentId = consent.getId();

        IdempotencyStore idempotencyStore = new IdempotencyStore();
        rateLimiter = new RateLimiter(properties, clock);
        paymentService = new PaymentService(idempotencyStore, rateLimiter, consentService, ledgerService, clock);
    }

    private PaymentService.PaymentResult pay(String key, String amount) {
        return paymentService.submit("partner-a", key, consentId, "ACCT-001",
                "Shop", "EXT-9", new BigDecimal(amount), "EUR");
    }

    @Test
    @DisplayName("同幂等键同报文重复提交只生效一次，回放同一 paymentId")
    void sameKeySamePayloadAppliesOnce() {
        PaymentService.PaymentResult first = pay("key-1", "20.00");
        assertThat(first.replayed()).isFalse();
        assertThat(first.record().getStatus()).isEqualTo(IdempotencyRecord.STATUS_SUCCESS);
        String paymentId = first.record().getPaymentId();

        PaymentService.PaymentResult second = pay("key-1", "20.00");
        assertThat(second.replayed()).isTrue();
        assertThat(second.record().getPaymentId()).isEqualTo(paymentId);
        log.info("[TEST] idempotency | idempotencyKey=key-1 | judge=second is REPLAY of {} | basis=same key+hash", paymentId);

        assertThat(balance()).isEqualByComparingTo("980.00");
        long debitEntries = ledgerService.entries().stream()
                .filter(e -> e.paymentId().equals(paymentId) && e.direction().equals(LedgerService.DEBIT))
                .count();
        assertThat(debitEntries).isEqualTo(1L);
    }

    @Test
    @DisplayName("同幂等键不同报文明确冲突 IDEMPOTENCY_CONFLICT，余额不变")
    void sameKeyDifferentPayloadConflicts() {
        pay("key-2", "10.00");
        assertThatThrownBy(() -> pay("key-2", "11.00"))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        log.info("[TEST] idempotency | idempotencyKey=key-2 | judge=CONFLICT | basis=payload hash differs");
        assertThat(balance()).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("被限流的请求不改变余额；不同幂等键各自生效")
    void rateLimitedRequestDoesNotChangeBalance() {
        GatewayProperties tight = new GatewayProperties();
        tight.setRateLimitPermitsPerMinute(1);
        tight.setRateLimitBurstCapacity(2);
        RateLimiter limiter = new RateLimiter(tight, clock);
        IdempotencyStore store = new IdempotencyStore();
        PaymentService svc = new PaymentService(store, limiter, consentService, ledgerService, clock);

        svc.submit("partner-a", "rk-1", consentId, "ACCT-001", "S", "E", new BigDecimal("5.00"), "EUR");
        svc.submit("partner-a", "rk-2", consentId, "ACCT-001", "S", "E", new BigDecimal("5.00"), "EUR");
        assertThatThrownBy(() ->
                svc.submit("partner-a", "rk-3", consentId, "ACCT-001", "S", "E", new BigDecimal("5.00"), "EUR"))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.RATE_LIMITED);
        log.info("[TEST] rate-limit | idempotencyKey=rk-3 | judge=RATE_LIMITED | basis=burst capacity 2 exhausted");
        // 仅前两笔各扣 5，第三笔被限流未扣
        assertThat(balance()).isEqualByComparingTo("990.00");
    }

    @Test
    @DisplayName("余额不足失败可区分且不落账；同键重试回放失败结果")
    void insufficientFundsRejectedAndReplayable() {
        assertThatThrownBy(() -> pay("key-3", "999999.00"))
                .isInstanceOf(GatewayException.class)
                .extracting(ex -> ((GatewayException) ex).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(balance()).isEqualByComparingTo("1000.00");
        // 终态失败已缓存：同键同报文再次提交回放 FAILED，仍无副作用
        PaymentService.PaymentResult replay = pay("key-3", "999999.00");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.record().getStatus()).isEqualTo(IdempotencyRecord.STATUS_FAILED);
        assertThat(replay.record().getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS.code());
        assertThat(balance()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("并发重复提交：N 个线程同一幂等键同报文，仅一笔生效，余额扣一次，账本守恒")
    void concurrentDuplicateSubmissionsApplyExactlyOnce() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        // 先提交全部任务（均在起点栅栏等待），再一次性放行，最大化并发竞争
        List<Future<PaymentService.PaymentResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return pay("key-concurrent", "30.00");
            }));
        }
        start.countDown();

        List<PaymentService.PaymentResult> results = new ArrayList<>();
        for (Future<PaymentService.PaymentResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long successApplied = results.stream().filter(r -> !r.replayed()).count();
        long replayed = results.stream().filter(PaymentService.PaymentResult::replayed).count();
        long distinctPaymentIds = results.stream().map(r -> r.record().getPaymentId()).distinct().count();
        log.info("[TEST] concurrent-duplicate | idempotencyKey=key-concurrent | threads={} | applied={} replayed={} distinctPaymentIds={} | judge=exactly-once",
                threads, successApplied, replayed, distinctPaymentIds);

        assertThat(successApplied).isEqualTo(1L);
        assertThat(replayed).isEqualTo(threads - 1L);
        assertThat(distinctPaymentIds).isEqualTo(1L);
        assertThat(balance()).isEqualByComparingTo("970.00");
        // 金额守恒：全部条目净额为 0；该付款恰好两条配对条目
        assertThat(ledgerService.totalNet()).isEqualByComparingTo("0");
        long paymentEntries = ledgerService.entries().stream()
                .filter(e -> results.get(0).record().getPaymentId().equals(e.paymentId())).count();
        assertThat(paymentEntries).isEqualTo(2L);
    }

    @Test
    @DisplayName("账本始终复式守恒：多笔不同幂等键支付后净额为 0，清算账户入账等于付款方扣款合计")
    void ledgerConservationAcrossPayments() {
        pay("c-1", "10.00");
        pay("c-2", "15.50");
        pay("c-3", "4.50");
        List<LedgerEntry> entries = ledgerService.entries();
        assertThat(entries).hasSize(6);
        assertThat(ledgerService.totalNet()).isEqualByComparingTo("0");
        assertThat(ledgerService.netOf("ACCT-001")).isEqualByComparingTo("-30.00");
        assertThat(ledgerService.netOf(LedgerService.clearingAccount("EUR"))).isEqualByComparingTo("30.00");
        assertThat(balance()).isEqualByComparingTo("970.00");
        log.info("[TEST] ledger | judge=conservation | payerNet={} clearingNet={} totalNet={}",
                ledgerService.netOf("ACCT-001"),
                ledgerService.netOf(LedgerService.clearingAccount("EUR")),
                ledgerService.totalNet());
    }

    private BigDecimal balance() {
        return accountRepository.findById("ACCT-001").orElseThrow().getBalance();
    }

    static final class FixedClock extends Clock {
        private final Instant instant;

        FixedClock(Instant instant) {
            this.instant = instant;
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
}
