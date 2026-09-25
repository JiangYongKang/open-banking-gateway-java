package com.github.highcumontoa.openbankinggatewayjava.web;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 全局异常处理：将业务异常与兜底异常映射为统一响应结构。
 * 对外仅暴露稳定的 HTTP 状态码、错误码与固定文案；内部日志保留可解释的细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ApiError> handleGateway(GatewayException ex) {
        ErrorCode code = ex.errorCode();
        // 内部日志保留拒绝判定依据；响应体对所有调用方保持一致文案，避免泄漏资源归属
        log.debug("gateway rejection | code={} | reason={}", code.code(), ex.getMessage());
        ApiError body = new ApiError(code.httpStatus(), code.code(), code.code(), Instant.now());
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus())).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception ex) {
        // 兜底：不向前端回传内部异常细节（防止泄漏），仅在服务端记录
        log.error("unexpected error", ex);
        ApiError body = new ApiError(
                ErrorCode.INTERNAL_ERROR.httpStatus(),
                ErrorCode.INTERNAL_ERROR.code(),
                ErrorCode.INTERNAL_ERROR.code(),
                Instant.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
