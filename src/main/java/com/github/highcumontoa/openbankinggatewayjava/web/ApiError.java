package com.github.highcumontoa.openbankinggatewayjava.web;

import java.time.Instant;

/**
 * 统一错误响应体：对外格式稳定，errorCode 语义可区分，不泄漏资源归属信息。
 */
public record ApiError(
        int httpStatus,
        String errorCode,
        String message,
        Instant timestamp
) {
}
