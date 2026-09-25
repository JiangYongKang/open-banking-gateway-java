package com.github.highcumontoa.openbankinggatewayjava.tests;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.security.PartnerRateLimiter;
import com.github.highcumontoa.openbankinggatewayjava.service.ConsentService;
import com.github.highcumontoa.openbankinggatewayjava.service.PaymentService;
import com.github.highcumontoa.openbankinggatewayjava.store.AccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.ConsentRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.IdempotencyStore;
import com.github.highcumontoa.openbankinggatewayjava.store.InMemoryAccountRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.InMemoryConsentRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.InMemoryLedgerRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.InMemoryPaymentRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.LedgerRepository;
import com.github.highcumontoa.openbankinggatewayjava.store.PaymentRepository;

/**
 * 纯单测装配：与 Spring 容器相同的组件组合，不依赖上下文。
 */
public final class TestFixtures {

    public record Harness(
            ConsentRepository consentRepository,
            AccountRepository accountRepository,
            PaymentRepository paymentRepository,
            LedgerRepository ledgerRepository,
            IdempotencyStore idempotencyStore,
            GatewayProperties properties,
            ConsentService consentService,
            PaymentService paymentService) {
    }

    private TestFixtures() {
    }

    public static Harness newHarness() {
        return newHarness(new GatewayProperties());
    }

    public static Harness newHarness(GatewayProperties properties) {
        ConsentRepository consents = new InMemoryConsentRepository();
        AccountRepository accounts = new InMemoryAccountRepository();
        PaymentRepository payments = new InMemoryPaymentRepository();
        LedgerRepository ledger = new InMemoryLedgerRepository();
        IdempotencyStore idempotency = new IdempotencyStore();
        PartnerRateLimiter limiter = new PartnerRateLimiter(properties);
        ConsentService consentService = new ConsentService(consents, properties);
        PaymentService paymentService = new PaymentService(consentService, accounts, payments,
                ledger, idempotency, limiter, properties);
        return new Harness(consents, accounts, payments, ledger, idempotency,
                properties, consentService, paymentService);
    }
}
