package com.github.highcumontoa.openbankinggatewayjava.payment;

import com.github.highcumontoa.openbankinggatewayjava.security.PartnerPrincipal;
import com.github.highcumontoa.openbankinggatewayjava.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 支付指令 REST：POST /api/payments，需 Header: Idempotency-Key 与 scope=payments.create。
 * 首次成功 201；幂等回放 200（含同一 paymentId）。
 */
@RestController
public class PaymentController {

    public static final String SCOPE_PAYMENTS_CREATE = "payments.create";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public record PaymentRequest(
            String consentId,
            String accountId,
            String creditorName,
            String creditorAccount,
            BigDecimal amount,
            String currency) {
    }

    public record PaymentResponse(
            String paymentId,
            String idempotencyKey,
            String status,
            String errorCode,
            boolean replayed) {
    }

    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PaymentRequest request,
            HttpServletRequest httpRequest) {

        PartnerPrincipal principal = SecuritySupport.principal(httpRequest);
        SecuritySupport.requireScope(principal, SCOPE_PAYMENTS_CREATE);

        PaymentService.PaymentResult result = paymentService.submit(
                principal.partnerId(),
                idempotencyKey,
                request.consentId(),
                request.accountId(),
                request.creditorName(),
                request.creditorAccount(),
                request.amount(),
                request.currency());

        IdempotencyRecord record = record(result);
        PaymentResponse body = new PaymentResponse(record.getPaymentId(), record.getIdempotencyKey(),
                record.getStatus(), record.getErrorCode(), result.replayed());
        // 终态失败返回 200 且携带 status=FAILED 与可区分 errorCode；成功首次 201，回放 200
        if (IdempotencyRecord.STATUS_FAILED.equals(record.getStatus())) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    private IdempotencyRecord record(PaymentService.PaymentResult result) {
        return result.record();
    }
}
