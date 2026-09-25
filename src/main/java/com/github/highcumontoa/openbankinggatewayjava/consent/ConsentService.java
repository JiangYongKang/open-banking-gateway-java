package com.github.highcumontoa.openbankinggatewayjava.consent;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同意书领域服务。
 *
 * <p>所有状态读取-判定-迁移都在 {@code lockOf(consentId)} 这把按同意书维度的锁内完成，
 * 因此“撤销”与“支付/查询”对同一同意书串行化，不会出现半生效状态；
 * 过期采用到达时刻懒迁移（AWAITING/AUTHORISED/SUSPENDED -> EXPIRED）。
 *
 * <p>跨合作方访问与资源不存在统一返回 RESOURCE_NOT_FOUND，错误响应无差异，不泄漏归属。
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final ConsentRepository repository;
    private final GatewayProperties properties;
    private final Clock clock;

    /** 按同意书 id 维度的锁表：保证同一同意书上的状态迁移与使用判定互斥。 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ConsentService(ConsentRepository repository, GatewayProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public Object lockOf(String consentId) {
        return locks.computeIfAbsent(consentId, k -> new Object());
    }

    public Consent create(String partnerId, Set<String> requestedScopes, Set<String> accountIds) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "scopes must not be empty");
        }
        if (accountIds == null || accountIds.isEmpty()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "accountIds must not be empty");
        }
        String id = "consent-" + UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getConsentTtlSeconds(), ChronoUnit.SECONDS);
        Consent consent = new Consent(id, partnerId, Set.copyOf(requestedScopes), Set.copyOf(accountIds),
                now, expiresAt, ConsentStatus.AWAITING_AUTHORISATION);
        repository.save(consent);
        log.info("consent-transition | consentId={} | partner={} | transition=CREATE -> {} | expiresAt={} | scopes={} | accounts={}",
                id, partnerId, ConsentStatus.AWAITING_AUTHORISATION, expiresAt, requestedScopes, accountIds);
        return consent;
    }

    public Consent authorize(String partnerId, String consentId) {
        return transition(partnerId, consentId, ConsentAction.AUTHORIZE);
    }

    public Consent suspend(String partnerId, String consentId) {
        return transition(partnerId, consentId, ConsentAction.SUSPEND);
    }

    public Consent resume(String partnerId, String consentId) {
        return transition(partnerId, consentId, ConsentAction.RESUME);
    }

    public Consent revoke(String partnerId, String consentId) {
        return transition(partnerId, consentId, ConsentAction.REVOKE);
    }

    public Consent get(String partnerId, String consentId) {
        Consent consent = loadOwned(partnerId, consentId);
        synchronized (lockOf(consentId)) {
            applyExpiryIfDue(consent);
            return consent;
        }
    }

    /**
     * 供账户查询/支付复用的统一准入判定，<b>调用方必须已持有 {@code lockOf(consentId)}</b>，
     * 以保证“判定通过”与“后续生效”之间状态不会被撤销/过期打断。
     */
    public Consent requireUsable(String partnerId, String consentId, String requiredScope, String accountId) {
        Consent consent = loadOwned(partnerId, consentId);
        applyExpiryIfDue(consent);
        ConsentStatus status = consent.getStatus();
        switch (status) {
            case REVOKED -> throw new GatewayException(ErrorCode.CONSENT_REVOKED,
                    judge(consentId, status, "consent is revoked"));
            case EXPIRED -> throw new GatewayException(ErrorCode.CONSENT_EXPIRED,
                    judge(consentId, status, "now=" + clock.instant() + " expiresAt=" + consent.getExpiresAt()));
            case SUSPENDED -> throw new GatewayException(ErrorCode.CONSENT_SUSPENDED,
                    judge(consentId, status, "consent is suspended"));
            case AWAITING_AUTHORISATION -> throw new GatewayException(ErrorCode.CONSENT_NOT_AUTHORIZED,
                    judge(consentId, status, "consent awaits authorisation"));
            case AUTHORISED -> { /* 通过状态判定 */ }
        }
        if (!consent.getScopes().contains(requiredScope)) {
            throw new GatewayException(ErrorCode.CONSENT_SCOPE_MISMATCH,
                    "scope-denied | consentId=" + consentId + " | required=" + requiredScope
                            + " | granted=" + consent.getScopes());
        }
        if (accountId != null && !consent.getAccountIds().contains(accountId)) {
            throw new GatewayException(ErrorCode.CONSENT_SCOPE_MISMATCH,
                    "account-denied | consentId=" + consentId + " | account=" + accountId
                            + " | allowed=" + consent.getAccountIds());
        }
        log.info("consent-usable | consentId={} | partner={} | status={} | scope={} | account={} | verdict=ALLOW",
                consentId, partnerId, status, requiredScope, accountId);
        return consent;
    }

    private Consent transition(String partnerId, String consentId, ConsentAction action) {
        Consent consent = loadOwned(partnerId, consentId);
        synchronized (lockOf(consentId)) {
            applyExpiryIfDue(consent);
            ConsentStatus current = consent.getStatus();
            ConsentStatus next = legalNext(current, action, consentId);
            consent.changeStatus(next, clock);
            repository.save(consent);
            log.info("consent-transition | consentId={} | partner={} | action={} | transition={} -> {} | basis=legal",
                    consentId, partnerId, action, current, next);
            return consent;
        }
    }

    private ConsentStatus legalNext(ConsentStatus current, ConsentAction action, String consentId) {
        // 终态 / 暂停态 / 待授权态的非法迁移在此给出可区分原因
        if (current == ConsentStatus.EXPIRED) {
            throw new GatewayException(ErrorCode.CONSENT_EXPIRED,
                    judge(consentId, current, "expired consent is terminal for action " + action));
        }
        if (current == ConsentStatus.REVOKED) {
            if (action == ConsentAction.REVOKE) {
                // 撤销幂等：重复撤销直接维持终态
                return ConsentStatus.REVOKED;
            }
            throw new GatewayException(ErrorCode.CONSENT_REVOKED,
                    judge(consentId, current, "revoked consent is terminal for action " + action));
        }
        switch (action) {
            case AUTHORIZE -> {
                if (current != ConsentStatus.AWAITING_AUTHORISATION) {
                    throw illegal(consentId, current, action, "only AWAITING_AUTHORISATION can be authorised");
                }
                return ConsentStatus.AUTHORISED;
            }
            case SUSPEND -> {
                if (current != ConsentStatus.AUTHORISED) {
                    throw illegal(consentId, current, action, "only AUTHORISED can be suspended");
                }
                return ConsentStatus.SUSPENDED;
            }
            case RESUME -> {
                if (current != ConsentStatus.SUSPENDED) {
                    throw illegal(consentId, current, action, "only SUSPENDED can be resumed");
                }
                return ConsentStatus.AUTHORISED;
            }
            case REVOKE -> {
                // AWAITING / AUTHORISED / SUSPENDED 均可撤销
                return ConsentStatus.REVOKED;
            }
        }
        throw new IllegalStateException("unreachable action " + action);
    }

    /** 到达过期时刻且尚未终止时，懒迁移到 EXPIRED。 */
    private void applyExpiryIfDue(Consent consent) {
        Instant now = clock.instant();
        ConsentStatus current = consent.getStatus();
        if (current != ConsentStatus.REVOKED && current != ConsentStatus.EXPIRED && consent.isExpiredAt(now)) {
            consent.changeStatus(ConsentStatus.EXPIRED, clock);
            repository.save(consent);
            log.info("consent-transition | consentId={} | transition={} -> EXPIRED | basis=ttl-expiry | now={} expiresAt={}",
                    consent.getId(), current, now, consent.getExpiresAt());
        }
    }

    /** 加载并校验归属；不存在或归属他人返回完全相同的 NOT_FOUND，杜绝归属泄漏。 */
    private Consent loadOwned(String partnerId, String consentId) {
        Consent consent = repository.findById(consentId)
                .orElseThrow(() -> {
                    log.warn("consent-access | consentId={} | partner={} | verdict=DENY | reason=not-found",
                            consentId, partnerId);
                    return new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, "consent not found");
                });
        if (!consent.getPartnerId().equals(partnerId)) {
            // 对外与 not-found 完全一致，仅在服务端日志记录真实判定依据
            log.warn("consent-access | consentId={} | partner={} | owner={} | verdict=DENY | reason=cross-partner",
                    consentId, partnerId, consent.getPartnerId());
            throw new GatewayException(ErrorCode.RESOURCE_NOT_FOUND, "consent not found");
        }
        return consent;
    }

    private GatewayException illegal(String consentId, ConsentStatus current, ConsentAction action, String basis) {
        return new GatewayException(ErrorCode.INVALID_CONSENT_TRANSITION,
                "illegal-transition | consentId=" + consentId + " | action=" + action
                        + " | current=" + current + " | basis=" + basis);
    }

    private String judge(String consentId, ConsentStatus status, String basis) {
        return "judge | consentId=" + consentId + " | status=" + status + " | basis=" + basis;
    }

    private enum ConsentAction {
        AUTHORIZE, SUSPEND, RESUME, REVOKE
    }
}
