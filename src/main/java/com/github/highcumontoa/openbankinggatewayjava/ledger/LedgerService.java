package com.github.highcumontoa.openbankinggatewayjava.ledger;

import com.github.highcumontoa.openbankinggatewayjava.account.Account;
import com.github.highcumontoa.openbankinggatewayjava.account.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账本服务（复式记账的内存简化版）。
 *
 * <p>每笔支付在<b>单一总账锁</b>内原子完成：余额充足性校验 → 付款方扣减 → 清算账户入账 →
 * 写入两条配对条目（DEBIT 付款方 / CREDIT 清算账户），任何一步失败则整体不生效（无中间落库）。
 *
 * <p>方向带符号：DEBIT 记负、CREDIT 记正；一笔支付两条金额相等方向相反，全量条目净额恒为 0
 * （账本可解释、金额守恒）。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";

    private final AccountRepository accountRepository;
    private final Clock clock;
    private final Object ledgerLock = new Object();
    private final List<LedgerEntry> entries = new ArrayList<>();

    public LedgerService(AccountRepository accountRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** 总账锁：支付服务以固定顺序（幂等键锁 -> 同意书锁 -> 总账锁）嵌套，避免死锁。 */
    public Object ledgerLock() {
        return ledgerLock;
    }

    /**
     * 过账一笔支付。调用方必须已持有 {@link #ledgerLock()}（由支付服务统一嵌套）。
     *
     * @throws GatewayException RESOURCE_NOT_FOUND 账户不存在；INSUFFICIENT_FUNDS 余额不足；
     *                          VALIDATION_ERROR 金额非法或币种不符
     */
    public void postPayment(String paymentId, String debitAccountId, BigDecimal amount, String currency) {
        synchronized (ledgerLock) {
            if (amount == null || amount.signum() <= 0) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR, "amount must be positive");
            }
            Account payer = accountRepository.findById(debitAccountId)
                    .orElseThrow(() -> new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, "account not found"));
            if (!payer.getCurrency().equals(currency)) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                        "currency mismatch: account=" + payer.getCurrency() + " request=" + currency);
            }
            if (payer.getBalance().compareTo(amount) < 0) {
                log.warn("ledger-post | paymentId={} | account={} | verdict=DENY | reason=insufficient-funds | balance={} amount={}",
                        paymentId, debitAccountId, payer.getBalance(), amount);
                throw new GatewayException(ErrorCode.INSUFFICIENT_FUNDS,
                        "balance=" + payer.getBalance() + " amount=" + amount);
            }
            String clearingAccountId = clearingAccount(currency);

            // 防御性幂等：同一 paymentId 绝不允许重复过账
            for (LedgerEntry e : entries) {
                if (e.paymentId().equals(paymentId) && e.direction().equals(DEBIT)) {
                    throw new GatewayException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "payment already posted: " + paymentId);
                }
            }

            payer.setBalance(payer.getBalance().subtract(amount));
            Account clearing = accountRepository.findById(clearingAccountId).orElseThrow();
            clearing.setBalance(clearing.getBalance().add(amount));

            entries.add(new LedgerEntry(UUID.randomUUID().toString(), paymentId, debitAccountId,
                    DEBIT, amount.negate(), currency, clock.instant()));
            entries.add(new LedgerEntry(UUID.randomUUID().toString(), paymentId, clearingAccountId,
                    CREDIT, amount, currency, clock.instant()));

            log.info("ledger-post | paymentId={} | debit={} | credit={} | amount={} {} | payerBalanceAfter={} | verdict=POSTED",
                    paymentId, debitAccountId, clearingAccountId, amount, currency, payer.getBalance());
        }
    }

    public static String clearingAccount(String currency) {
        return "CLEARING-" + currency;
    }

    /** 全部账本条目（快照）。 */
    public List<LedgerEntry> entries() {
        synchronized (ledgerLock) {
            return List.copyOf(entries);
        }
    }

    /** 某账户全部条目的净额（正为贷入，负为借记）。 */
    public BigDecimal netOf(String accountId) {
        synchronized (ledgerLock) {
            return entries.stream()
                    .filter(e -> e.accountId().equals(accountId))
                    .map(LedgerEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** 全量条目净额：内部复式记账下恒为 0。 */
    public BigDecimal totalNet() {
        synchronized (ledgerLock) {
            return entries.stream().map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
