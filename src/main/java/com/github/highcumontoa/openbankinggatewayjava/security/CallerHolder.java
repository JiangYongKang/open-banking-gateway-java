package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;

/**
 * 以请求属性承载调用方身份（无状态，不使用 ThreadLocal 避免线程池串号）。
 */
public final class CallerHolder {

    public static final String REQUEST_ATTRIBUTE = CallerHolder.class.getName() + ".caller";

    private CallerHolder() {
    }

    public static CallerContext require(jakarta.servlet.http.HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof CallerContext context) {
            return context;
        }
        throw new com.github.highcumontoa.openbankinggatewayjava.error.GatewayException(
                com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode.UNAUTHENTICATED,
                "caller context missing");
    }
}
