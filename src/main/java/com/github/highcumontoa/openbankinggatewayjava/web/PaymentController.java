package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.api.PaymentLookupResponse;
import com.github.highcumontoa.openbankinggatewayjava.api.PaymentRequest;
import com.github.highcumontoa.openbankinggatewayjava.api.PaymentResponse;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import com.github.highcumontoa.openbankinggatewayjava.domain.Scope;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import com.github.highcumontoa.openbankinggatewayjava.security.CallerHolder;
import com.github.highcumontoa.openbankinggatewayjava.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付指令提交与回查：令牌须含 PAYMENTS_INITIATE；提交时幂等键必传。
 * 回查按 (partner, Idempotency-Key) 返回首次判定结果（成功或业务拒绝），不重新执行。
 */
@RestController
public class PaymentController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/api/payments")
    public ResponseEntity<PaymentResponse> submit(HttpServletRequest http,
                                                  @RequestBody PaymentRequest request) {
        CallerContext caller = CallerHolder.require(http);
        if (!caller.hasScope(Scope.PAYMENTS_INITIATE)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE, "token requires scope PAYMENTS_INITIATE");
        }
        String key = http.getHeader(IDEMPOTENCY_HEADER);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PaymentResponse.from(paymentService.submit(caller, key, request)));
    }

    @GetMapping("/api/payments/by-idempotency/{idempotencyKey}")
    public PaymentLookupResponse lookup(HttpServletRequest http,
                                        @PathVariable String idempotencyKey) {
        CallerContext caller = CallerHolder.require(http);
        if (!caller.hasScope(Scope.PAYMENTS_INITIATE)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE, "token requires scope PAYMENTS_INITIATE");
        }
        return PaymentLookupResponse.from(paymentService.lookup(caller, idempotencyKey));
    }
}
