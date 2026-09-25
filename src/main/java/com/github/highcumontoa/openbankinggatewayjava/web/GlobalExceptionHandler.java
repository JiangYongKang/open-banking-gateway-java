package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.error.ApiError;
import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误出口：任何业务异常都映射为稳定的 HTTP 状态码 + code + reason，
 * 保证对外语义一致；NOT_FOUND 不区分"不存在/跨方"，避免泄漏资源归属。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException ex) {
        ErrorCode code = ex.errorCode();
        ApiError body = new ApiError(code.code(), code.code(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        ApiError body = new ApiError(ErrorCode.VALIDATION_ERROR.code(),
                ErrorCode.VALIDATION_ERROR.code(), "malformed request body");
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.httpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        ApiError body = new ApiError("internal_error", "internal_error", "request failed");
        return ResponseEntity.internalServerError().body(body);
    }
}
