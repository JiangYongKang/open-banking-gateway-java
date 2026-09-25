package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求中读取已认证身份并做授权范围判定的小工具。
 */
public final class SecuritySupport {

    private SecuritySupport() {
    }

    public static PartnerPrincipal principal(HttpServletRequest request) {
        Object attr = request.getAttribute(SecurityInterceptor.PRINCIPAL_ATTRIBUTE);
        if (attr instanceof PartnerPrincipal principal) {
            return principal;
        }
        throw new GatewayException(ErrorCode.UNAUTHENTICATED, "unauthenticated request");
    }

    /** 范围不足稳定映射为 403 INSUFFICIENT_SCOPE。 */
    public static void requireScope(PartnerPrincipal principal, String scope) {
        if (!principal.scopes().contains(scope)) {
            throw new GatewayException(ErrorCode.INSUFFICIENT_SCOPE,
                    "partner=" + principal.partnerId() + " requires scope=" + scope);
        }
    }
}
