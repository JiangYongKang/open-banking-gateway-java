package com.github.highcumontoa.openbankinggatewayjava.service;

import com.github.highcumontoa.openbankinggatewayjava.api.ConsentRequest;
import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import com.github.highcumontoa.openbankinggatewayjava.domain.ConsentStatus;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.store.ConsentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * 同意书状态机与授权边界。所有读取都先经归属校验：
 * 资源不存在与跨合作方访问共享同一对外错误形态（NOT_FOUND），不泄漏归属。
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final ConsentRepository consentRepository;
    private final GatewayProperties properties;

    public ConsentService(ConsentRepository consentRepository, GatewayProperties properties) {
        this.consentRepository = consentRepository;
        this.properties = properties;
    }

    public Consent create(CallerContext caller, ConsentRequest request) {
        requireManage(caller, "create");
        validateCreateRequest(request);
        if (!caller.scopes().containsAll(request.getScopes())) {
            log.info("consent-create partner={} reason=scope_not_granted requested={} tokenScopes={}",
                    caller.partnerId(), request.getScopes(), caller.scopes());
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "consent scopes must be a subset of the token scopes");
        }
        Instant now = Instant.now();
        Consent consent = new Consent(
                caller.partnerId(),
                Set.copyOf(request.getAccountIds()),
                Set.copyOf(request.getScopes()),
                now.plus(properties.getConsent().getDefaultTtl()));
        consentRepository.save(consent);
        log.info("consent-create consentId={} partner={} accounts={} scopes={} ttl={} -> {}",
                consent.id(), caller.partnerId(), consent.accountIds(), consent.scopes(),
                properties.getConsent().getDefaultTtl(), consent.status());
        return consent;
    }

    public Consent authorize(CallerContext caller, String consentId) {
        return transition(caller, consentId, ConsentStatus.AUTHORIZED, "authorize");
    }

    public Consent suspend(CallerContext caller, String consentId) {
        return transition(caller, consentId, ConsentStatus.SUSPENDED, "suspend");
    }

    public Consent resume(CallerContext caller, String consentId) {
        return transition(caller, consentId, ConsentStatus.AUTHORIZED, "resume");
    }

    public Consent revoke(CallerContext caller, String consentId) {
        return transition(caller, consentId, ConsentStatus.REVOKED, "revoke");
    }

    /**
     * 只读列表：仅返回本合作方名下同意书，按创建时间排序。
     * 只读范围（CONSENTS_READ）可用；跨合作方数据天然不可见。
     */
    public java.util.List<Consent> list(CallerContext caller) {
        requireRead(caller, "list");
        return consentRepository.findAll().stream()
                .filter(c -> c.partnerId().equals(caller.partnerId()))
                .sorted(java.util.Comparator.comparing(Consent::createdAt))
                .toList();
    }

    /**
     * 只读详情：与变更操作共享同一归属语义——不存在与跨合作方一律 NOT_FOUND。
     */
    public Consent getDetail(CallerContext caller, String consentId) {
        requireRead(caller, "detail");
        return getOwned(caller, consentId);
    }

    /**
     * 一致性快照：在同意书对象锁内一次性读出状态、版本与可用性，
     * 与状态迁移互斥，读到的必然是某次成功迁移之前或之后的完整状态。
     */
    public com.github.highcumontoa.openbankinggatewayjava.domain.ConsentSnapshot snapshot(Consent consent) {
        synchronized (consent) {
            return new com.github.highcumontoa.openbankinggatewayjava.domain.ConsentSnapshot(
                    consent.id(), consent.partnerId(), consent.accountIds(), consent.scopes(),
                    consent.createdAt(), consent.expiresAt(), consent.status(), consent.version(),
                    consent.updatedAt(), effectiveStatus(consent) == ConsentStatus.AUTHORIZED);
        }
    }

    private void requireRead(CallerContext caller, String action) {
        if (!caller.canReadConsents()) {
            log.info("consent-{} partner={} reason=read_scope_missing tokenScopes={}",
                    action, caller.partnerId(), caller.scopes());
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "token requires scope CONSENTS_READ or CONSENTS_MANAGE");
        }
    }

    private void requireManage(CallerContext caller, String action) {
        if (!caller.canManageConsents()) {
            log.info("consent-{} partner={} reason=manage_scope_missing tokenScopes={}",
                    action, caller.partnerId(), caller.scopes());
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "token requires scope CONSENTS_MANAGE");
        }
    }

    /**
     * 仅供支付/查询服务使用的强一致读取：完成归属、有效期、状态与范围四类判定，
     * 每类失败映射到可区分错误码。
     */
    public Consent requireUsable(CallerContext caller, String consentId, Scope requiredScope) {
        Consent consent = getOwned(caller, consentId);
        ConsentStatus effective = effectiveStatus(consent);
        if (effective == ConsentStatus.EXPIRED) {
            log.info("consent-check consentId={} partner={} reason=expired expiresAt={}",
                    consentId, caller.partnerId(), consent.expiresAt());
            throw new GatewayException(ErrorCode.CONSENT_EXPIRED, "consent expired at " + consent.expiresAt());
        }
        if (effective == ConsentStatus.REVOKED) {
            log.info("consent-check consentId={} partner={} reason=revoked", consentId, caller.partnerId());
            throw new GatewayException(ErrorCode.CONSENT_REVOKED, "consent has been revoked");
        }
        if (effective != ConsentStatus.AUTHORIZED) {
            log.info("consent-check consentId={} partner={} reason=invalid_state status={} required=AUTHORIZED",
                    consentId, caller.partnerId(), effective);
            throw new GatewayException(ErrorCode.INVALID_CONSENT_STATE,
                    "consent not authorized, current status: " + effective);
        }
        if (!consent.scopes().contains(requiredScope)) {
            log.info("consent-check consentId={} partner={} reason=scope_mismatch required={} granted={}",
                    consentId, caller.partnerId(), requiredScope, consent.scopes());
            throw new GatewayException(ErrorCode.CONSENT_SCOPE_MISMATCH,
                    "consent does not grant scope " + requiredScope);
        }
        return consent;
    }

    public void requireAccountInConsent(Consent consent, String accountId) {
        if (!consent.accountIds().contains(accountId)) {
            log.info("consent-check consentId={} reason=account_not_allowed accountId={} allowed={}",
                    consent.id(), accountId, consent.accountIds());
            throw new GatewayException(ErrorCode.ACCOUNT_NOT_IN_CONSENT,
                    "account is not covered by consent: " + accountId);
        }
    }

    /**
     * 归属读取：不存在 / 归属他人一律 NOT_FOUND（对外不区分），内部 reason 可区分。
     */
    public Consent getOwned(CallerContext caller, String consentId) {
        Consent consent = consentRepository.findById(consentId)
                .orElseThrow(() -> {
                    log.info("consent-access partner={} consentId={} reason=not_found",
                            caller.partnerId(), consentId);
                    return new GatewayException(ErrorCode.NOT_FOUND, "consent not found: " + consentId);
                });
        if (!consent.partnerId().equals(caller.partnerId())) {
            log.info("consent-access partner={} consentId={} reason=cross_partner owner={}",
                    caller.partnerId(), consentId, consent.partnerId());
            // 故意与"不存在"完全同构，避免响应差异泄漏资源归属
            throw new GatewayException(ErrorCode.NOT_FOUND, "consent not found: " + consentId);
        }
        return consent;
    }

    private Consent transition(CallerContext caller, String consentId, ConsentStatus target, String action) {
        requireManage(caller, action);
        Consent consent = getOwned(caller, consentId);
        ConsentStatus current = effectiveStatus(consent);
        if (!allowed(current, target)) {
            ErrorCode code = switch (current) {
                case EXPIRED -> ErrorCode.CONSENT_EXPIRED;
                case REVOKED -> ErrorCode.CONSENT_REVOKED;
                default -> ErrorCode.INVALID_CONSENT_STATE;
            };
            log.info("state-transition consentId={} partner={} {} : {} -> {} REJECTED reason={}",
                    consentId, caller.partnerId(), action, current, target, code.code());
            throw new GatewayException(code,
                    "illegal transition " + action + ": " + current + " -> " + target);
        }
        synchronized (consent) {
            ConsentStatus rechecked = effectiveStatus(consent);
            if (!allowed(rechecked, target)) {
                log.info("state-transition consentId={} partner={} {} : {} -> {} REJECTED on recheck",
                        consentId, caller.partnerId(), action, rechecked, target);
                throw new GatewayException(ErrorCode.INVALID_CONSENT_STATE,
                        "illegal transition after recheck: " + rechecked + " -> " + target);
            }
            // 成功迁移：版本单调递增、变更时间推进；读取侧只能看到迁移前或后的完整状态
            consent.markTransition(target, Instant.now());
            consentRepository.save(consent);
            log.info("state-transition consentId={} partner={} {} : {} -> {} ACCEPTED version={}",
                    consentId, caller.partnerId(), action, rechecked, target, consent.version());
        }
        return consent;
    }

    private ConsentStatus effectiveStatus(Consent consent) {
        if (consent.status() == ConsentStatus.REVOKED) {
            return ConsentStatus.REVOKED;
        }
        if (!consent.expiresAt().isAfter(Instant.now())) {
            return ConsentStatus.EXPIRED;
        }
        return consent.status();
    }

    private boolean allowed(ConsentStatus from, ConsentStatus to) {
        return switch (to) {
            case AUTHORIZED -> from == ConsentStatus.CREATED || from == ConsentStatus.SUSPENDED;
            case SUSPENDED -> from == ConsentStatus.AUTHORIZED;
            case REVOKED -> EnumSet.of(ConsentStatus.CREATED, ConsentStatus.AUTHORIZED, ConsentStatus.SUSPENDED)
                    .contains(from);
            default -> false;
        };
    }

    private void validateCreateRequest(ConsentRequest request) {
        if (request == null || request.getAccountIds() == null || request.getAccountIds().isEmpty()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "accountIds must not be empty");
        }
        if (request.getScopes() == null || request.getScopes().isEmpty()) {
            throw new GatewayException(ErrorCode.VALIDATION_ERROR, "scopes must not be empty");
        }
        for (Scope scope : request.getScopes()) {
            if (scope == null) {
                throw new GatewayException(ErrorCode.VALIDATION_ERROR, "scope must not be null");
            }
        }
    }
}
