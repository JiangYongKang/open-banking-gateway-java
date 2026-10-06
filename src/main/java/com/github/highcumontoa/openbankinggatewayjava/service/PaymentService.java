package com.github.highcumontoa.openbankinggatewayjava.service;

import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.Account;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;
import com.github.highcumontoa.openbankinggatewayjava.domain.LedgerEntry;
import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.store.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.IdempotencyStore;
import com.github.highcumontoa.openbankinggatewayjava.store.LedgerRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 支付指令服务。关键并发保证：
 * <ol>
 *   <li>同 (partner, idempotencyKey) 在键锁内串行：同键同报文回放、同键异报文冲突；</li>
 *   <li>同意书对象锁串行化"支付与撤销"，二者不会半生效；</li>
 *   <li>余额校验/扣减在付款账户锁内完成，不足额失败不动账；</li>
 *   <li>限流/校验失败均在记账之前返回，任何失败路径都不改变余额；</li>
 *   <li>业务拒绝（余额不足/同意书不可用/限流等）与成功一样落幂等记录：
 *       同键同报文回放当时的拒绝原因，同键异报文判冲突，绝不重新执行；
 *       记录有保留窗口，超窗可清理，不影响已生效支付与账务。</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final ConsentService consentService;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerRepository ledgerRepository;
    private final IdempotencyStore idempotencyStore;
    private final com.github.highcumontoa.openbankinggatewayjava.security.PartnerRateLimiter rateLimiter;
    private final GatewayProperties properties;

    public PaymentService(ConsentService consentService,
                          AccountRepository accountRepository,
                          PaymentRepository paymentRepository,
                          LedgerRepository ledgerRepository,
                          IdempotencyStore idempotencyStore,
                          com.github.highcumontoa.openbankinggatewayjava.security.PartnerRateLimiter rateLimiter,
                          GatewayProperties properties) {
        this.consentService = consentService;
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.ledgerRepository = ledgerRepository;
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    public Payment submit(CallerContext caller, String idempotencyKey, PaymentRequest request) {
        validate(idempotencyKey, request);

        // 1) 幂等：同键请求串行，先查是否已有结果可回放/冲突
        Object keyLock = idempotencyStore.lockFor(caller.partnerId(), idempotencyKey);
        synchronized (keyLock) {
            var existing = idempotencyStore.find(caller.partnerId(), idempotencyKey);
            String fingerprint = fingerprint(request);
            if (existing.isPresent()) {
                IdempotencyRecord record = existing.get();
                if (!record.requestFingerprint().equals(fingerprint)) {
                    log.info("idempotency key={} partner={} reason=conflict firstFp={} thisFp={}",
                            idempotencyKey, caller.partnerId(), record.requestFingerprint(), fingerprint);
                    throw new GatewayException(ErrorCode.IDEMPOTENCY_CONFLICT,
                            "idempotency key was used with a different request payload");
                }
                return replay(record, idempotencyKey, caller);
            }

            // 2) 限流（在幂等占位内、记账前；被限流不产生任何账务效果，但判定结果落回查记录）
            if (!rateLimiter.tryAcquire(caller.partnerId())) {
                log.info("payment-reject key={} partner={} reason=rate_limited",
                        idempotencyKey, caller.partnerId());
                GatewayException ex = new GatewayException(ErrorCode.RATE_LIMITED,
                        "payment rate limit exceeded for partner");
                idempotencyStore.put(IdempotencyRecord.rejected(
                        caller.partnerId(), idempotencyKey, fingerprint,
                        ex.errorCode(), ex.getMessage()));
                log.info("idempotency key={} partner={} reason=stored-rejection code={}",
                        idempotencyKey, caller.partnerId(), ex.errorCode().code());
                throw ex;
            }

            // 3) 执行业务；成功与业务拒绝都落幂等记录，保证可按键回查且不重新执行。
            try {
                Payment payment = execute(caller, idempotencyKey, request);
                idempotencyStore.put(IdempotencyRecord.completed(
                        caller.partnerId(), idempotencyKey, fingerprint, payment));
                log.info("idempotency key={} partner={} reason=stored paymentId={}",
                        idempotencyKey, caller.partnerId(), payment.id());
                return payment;
            } catch (GatewayException ex) {
                idempotencyStore.put(IdempotencyRecord.rejected(
                        caller.partnerId(), idempotencyKey, fingerprint,
                        ex.errorCode(), ex.getMessage()));
                log.info("idempotency key={} partner={} reason=stored-rejection code={}",
                        idempotencyKey, caller.partnerId(), ex.errorCode().code());
                throw ex;
            }
        }
    }

    /**
     * 按幂等键回查提交结果（成功与业务拒绝都可查）。
     * 无记录或记录已超保留窗口时，与"键不存在"同构返回 NOT_FOUND。
     */
    public IdempotencyRecord lookup(CallerContext caller, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "idempotency key is required");
        }
        return idempotencyStore.find(caller.partnerId(), idempotencyKey)
                .orElseThrow(() -> {
                    log.info("payment-lookup key={} partner={} reason=not_found",
                            idempotencyKey, caller.partnerId());
                    return new GatewayException(ErrorCode.NOT_FOUND,
                            "payment result not found for idempotency key: " + idempotencyKey);
                });
    }

    private Payment replay(IdempotencyRecord record, String idempotencyKey, CallerContext caller) {
        if (record.outcome() == IdempotencyRecord.Outcome.REJECTED) {
            log.info("idempotency key={} partner={} reason=replay-rejection code={}",
                    idempotencyKey, caller.partnerId(), record.rejectionCode().code());
            throw new GatewayException(record.rejectionCode(), record.rejectionMessage());
        }
        log.info("idempotency key={} partner={} reason=replay paymentId={}",
                idempotencyKey, caller.partnerId(), record.payment().id());
        return record.payment();
    }

    private Payment execute(CallerContext caller, String idempotencyKey, PaymentRequest request) {
        // 同意书锁：支付与撤销/暂停互斥，杜绝撤销与支付同时发生时的半生效
        Consent consent = consentService.requireUsable(caller, request.getConsentId(), Scope.PAYMENTS_INITIATE);
        synchronized (consent) {
            // 锁内二次判定：可能在拿到锁前已被撤销
            Consent rechecked = consentService.requireUsable(caller, consent.id(), Scope.PAYMENTS_INITIATE);
            consentService.requireAccountInConsent(rechecked, request.getFromAccountId());

            Account from = accountRepository.findById(request.getFromAccountId())
                    .orElseThrow(() -> new GatewayException(ErrorCode.ACCOUNT_NOT_FOUND,
                            "account not found: " + request.getFromAccountId()));
            Account to = accountRepository.findById(request.getToAccountId())
                    .orElseThrow(() -> new GatewayException(ErrorCode.ACCOUNT_NOT_FOUND,
                            "account not found: " + request.getToAccountId()));
            if (from.id().equals(to.id())) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR, "from and to account must differ");
            }

            Payment payment = new Payment(consent.id(), caller.partnerId(), from.id(), to.id(),
                    request.getAmount(), request.getCurrency());

            // 账户锁序：按账户 id 排序加锁，避免两笔反向转账死锁
            Account first = from.id().compareTo(to.id()) < 0 ? from : to;
            Account second = first == from ? to : from;
            synchronized (first) {
                synchronized (second) {
                    if (from.balance().compareTo(request.getAmount()) < 0) {
                        log.info("payment-reject key={} partner={} paymentId={} reason=insufficient_funds balance={} amount={}",
                                idempotencyKey, caller.partnerId(), payment.id(), from.balance(), request.getAmount());
                        throw new GatewayException(ErrorCode.INSUFFICIENT_FUNDS,
                                "account balance is insufficient");
                    }
                    // 分阶段提交，任一步失败按阶段反向补偿，绝不留下半生效状态
                    boolean debited = false;
                    boolean credited = false;
                    try {
                        accountRepository.adjustBalance(from.id(), request.getAmount().negate());
                        debited = true;
                        accountRepository.adjustBalance(to.id(), request.getAmount());
                        credited = true;
                        ledgerRepository.append(new LedgerEntry(
                                UUID.randomUUID().toString(), payment.id(), from.id(),
                                LedgerEntry.Direction.CREDIT, request.getAmount(), Instant.now()));
                        ledgerRepository.append(new LedgerEntry(
                                UUID.randomUUID().toString(), payment.id(), to.id(),
                                LedgerEntry.Direction.DEBIT, request.getAmount(), Instant.now()));
                        paymentRepository.save(payment);
                    } catch (RuntimeException ex) {
                        if (credited) {
                            accountRepository.adjustBalance(to.id(), request.getAmount().negate());
                        }
                        if (debited) {
                            accountRepository.adjustBalance(from.id(), request.getAmount());
                        }
                        log.warn("payment-compensated key={} paymentId={} reason=ledger_error msg={}",
                                idempotencyKey, payment.id(), ex.getMessage());
                        throw new GatewayException(ErrorCode.LEDGER_ERROR, "ledger update failed: " + ex.getMessage());
                    }
                }
            }
            log.info("payment-applied key={} partner={} paymentId={} consentId={} {} -> {} amount={} currency={}",
                    idempotencyKey, caller.partnerId(), payment.id(), consent.id(),
                    from.id(), to.id(), request.getAmount(), request.getCurrency());
            return payment;
        }
    }

    private void validate(String idempotencyKey, PaymentRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key header is required");
        }
        if (request == null) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "request body is required");
        }
        if (request.getConsentId() == null || request.getConsentId().isBlank()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "consentId is required");
        }
        if (request.getFromAccountId() == null || request.getToAccountId() == null) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "fromAccountId and toAccountId are required");
        }
        if (request.getAmount() == null
                || request.getAmount().signum() <= 0
                || request.getAmount().scale() > 2) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                    "amount must be positive with at most 2 decimal places");
        }
        if (!properties.getCurrency().equals(request.getCurrency())) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR,
                    "currency must be " + properties.getCurrency());
        }
    }

    private String fingerprint(PaymentRequest request) {
        String raw = String.join("|",
                nullToEmpty(request.getConsentId()),
                nullToEmpty(request.getFromAccountId()),
                nullToEmpty(request.getToAccountId()),
                request.getAmount() == null ? "" : request.getAmount().stripTrailingZeros().toPlainString(),
                nullToEmpty(request.getCurrency()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
