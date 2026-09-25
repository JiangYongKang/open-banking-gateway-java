package com.github.highcumontoa.openbankinggatewayjava.payment;

import com.github.highcumontoa.openbankinggatewayjava.consent.Consent;
import com.github.highcumontoa.openbankinggatewayjava.consent.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.ledger.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 支付服务。
 *
 * <p>单次提交的处理顺序与并发控制：
 * <ol>
 *   <li>报文与参数校验（非法直接拒绝，无副作用）；</li>
 *   <li>幂等键锁内查重：已有记录则“同报文回放 / 不同报文冲突”，均不再产生副作用；</li>
 *   <li>新请求先过合作方维度限流（RATE_LIMITED，不动余额）；</li>
 *   <li>按固定顺序加锁（幂等键锁 -> 同意书锁 -> 总账锁），在同意书锁内做状态/范围判定，
 *       在总账锁内原子过账；任一步失败都不会修改余额；</li>
 *   <li>终态结果（成功/终态业务失败）落幂等记录，之后同键同报文只回放。</li>
 * </ol>
 * 因此“撤销与支付同时发生”被串行化：要么支付在撤销前完整生效，要么支付观察到已撤销而拒绝，
 * 不存在半生效。
 */
@Service
public class PaymentService {

    /** 提交结果：幂等记录 + 是否为历史结果回放。 */
    public record PaymentResult(IdempotencyRecord record, boolean replayed) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** 同意书需要包含的授权范围（合作方令牌另需 payments.create）。 */
    public static final String CONSENT_SCOPE_PAYMENTS = "payments";

    private final IdempotencyStore idempotencyStore;
    private final RateLimiter rateLimiter;
    private final ConsentService consentService;
    private final LedgerService ledgerService;
    private final Clock clock;

    public PaymentService(IdempotencyStore idempotencyStore, RateLimiter rateLimiter,
                          ConsentService consentService, LedgerService ledgerService, Clock clock) {
        this.idempotencyStore = idempotencyStore;
        this.rateLimiter = rateLimiter;
        this.consentService = consentService;
        this.ledgerService = ledgerService;
        this.clock = clock;
    }

    public PaymentResult submit(
            String partnerId,
            String idempotencyKey,
            String consentId,
            String accountId,
            String creditorName,
            String creditorAccount,
            BigDecimal amount,
            String currency) {

        validate(idempotencyKey, consentId, accountId, creditorName, creditorAccount, amount, currency);
        String payloadHash = hash(canonicalPayload(
                consentId, accountId, creditorName, creditorAccount, amount, currency));

        // 第一层：同一 (合作方, 幂等键) 串行化，杜绝并发重复生效
        synchronized (idempotencyStore.lockOf(partnerId, idempotencyKey)) {
            Optional<IdempotencyRecord> existing = idempotencyStore.find(partnerId, idempotencyKey);
            if (existing.isPresent()) {
                return new PaymentResult(replayOrConflict(existing.get(), payloadHash), true);
            }

            // 仅对“新请求”计限流；被限流不得有任何余额/账本副作用
            if (!rateLimiter.tryAcquire(partnerId)) {
                throw new GatewayException(ErrorCode.RATE_LIMITED,
                        "idempotencyKey=" + idempotencyKey + " partner=" + partnerId);
            }

            String paymentId = "payment-" + UUID.randomUUID();
            try {
                // 第二、三层锁顺序固定：同意书锁 -> 总账锁，与其他路径一致，避免死锁
                synchronized (consentService.lockOf(consentId)) {
                    Consent consent = consentService.requireUsable(
                            partnerId, consentId, CONSENT_SCOPE_PAYMENTS, accountId);
                    synchronized (ledgerService.ledgerLock()) {
                        ledgerService.postPayment(paymentId, accountId, amount, currency);
                    }
                    log.info("payment-result | idempotencyKey={} | paymentId={} | partner={} | consentId={} | consentStatus={} | amount={} {} | verdict=SUCCESS",
                            idempotencyKey, paymentId, partnerId, consentId, consent.getStatus(), amount, currency);
                }
            } catch (GatewayException ex) {
                ErrorCode code = ex.errorCode();
                if (isTerminal(code)) {
                    IdempotencyRecord failed = new IdempotencyRecord(idempotencyKey, partnerId, payloadHash,
                            paymentId, IdempotencyRecord.STATUS_FAILED, code.code(), clock.instant());
                    idempotencyStore.save(failed);
                    log.warn("payment-result | idempotencyKey={} | paymentId={} | partner={} | verdict=FAILED | errorCode={} | terminal=true",
                            idempotencyKey, paymentId, partnerId, code.code());
                } else {
                    log.warn("payment-result | idempotencyKey={} | paymentId={} | partner={} | verdict=REJECTED | errorCode={} | terminal=false | notCached",
                            idempotencyKey, paymentId, partnerId, code.code());
                }
                throw ex;
            }

            IdempotencyRecord success = new IdempotencyRecord(idempotencyKey, partnerId, payloadHash,
                    paymentId, IdempotencyRecord.STATUS_SUCCESS, null, clock.instant());
            idempotencyStore.save(success);
            log.info("idempotency-store | idempotencyKey={} | paymentId={} | partner={} | payloadHash={} | action=save-success",
                    idempotencyKey, paymentId, partnerId, payloadHash);
            return new PaymentResult(success, false);
        }
    }

    /** 同键同报文回放原结果（不再次过账）；同键不同报文明确冲突。 */
    private IdempotencyRecord replayOrConflict(IdempotencyRecord record, String payloadHash) {
        if (!record.getPayloadHash().equals(payloadHash)) {
            log.warn("idempotency-conflict | idempotencyKey={} | partner={} | storedHash={} | requestHash={} | verdict=CONFLICT",
                    record.getIdempotencyKey(), record.getPartnerId(), record.getPayloadHash(), payloadHash);
            throw new GatewayException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "idempotencyKey=" + record.getIdempotencyKey()
                            + " is bound to a different payload");
        }
        log.info("idempotency-replay | idempotencyKey={} | paymentId={} | partner={} | status={} | errorCode={} | verdict=REPLAY",
                record.getIdempotencyKey(), record.getPaymentId(), record.getPartnerId(),
                record.getStatus(), record.getErrorCode());
        return record;
    }

    /**
     * 终态业务失败：在同一报文下重试结果不变，可安全缓存回放。
     * 非终态（限流等瞬时性失败）不缓存，允许客户端稍后重试。
     */
    private boolean isTerminal(ErrorCode code) {
        return code == ErrorCode.INSUFFICIENT_FUNDS
                || code == ErrorCode.CONSENT_REVOKED
                || code == ErrorCode.CONSENT_EXPIRED
                || code == ErrorCode.CONSENT_SCOPE_MISMATCH
                || code == ErrorCode.RESOURCE_NOT_FOUND
                || code == ErrorCode.VALIDATION_ERROR
                || code == ErrorCode.CONSENT_NOT_AUTHORIZED
                || code == ErrorCode.CONSENT_SUSPENDED;
    }

    private void validate(String idempotencyKey, String consentId, String accountId,
                          String creditorName, String creditorAccount, BigDecimal amount, String currency) {
        if (isBlank(idempotencyKey)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key is required");
        }
        if (isBlank(consentId) || isBlank(accountId)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "consentId and accountId are required");
        }
        if (isBlank(creditorName) || isBlank(creditorAccount)) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "creditorName and creditorAccount are required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "amount must be positive");
        }
        if (isBlank(currency) || currency.length() != 3) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "currency must be a 3-letter code");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 规范化报文：字段以固定分隔符拼接，避免不同 JSON 字段顺序导致指纹漂移。 */
    static String canonicalPayload(String consentId, String accountId, String creditorName,
                                   String creditorAccount, BigDecimal amount, String currency) {
        return String.join("\u001f",
                nullSafe(consentId), nullSafe(accountId), nullSafe(creditorName),
                nullSafe(creditorAccount), amount.stripTrailingZeros().toPlainString(),
                nullSafe(currency));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // 便于记录/日志使用
    static Instant now(Clock clock) {
        return clock.instant();
    }
}
