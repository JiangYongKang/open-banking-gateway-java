package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.error.ErrorCode;
import com.github.highcumontoa.openbankinggatewayjava.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 安全拦截器：解析 Bearer 令牌并写入调用方身份；
 * 令牌缺失/非法 -> UNAUTHENTICATED，过期 -> TOKEN_EXPIRED（状态码同为 401，错误码可区分）。
 * 范围（scope）不足在控制器内显式判定，映射为 INSUFFICIENT_SCOPE(403)。
 */
@Component
public class SecurityInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "obg.principal";

    private final TokenService tokenService;

    public SecurityInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        String rawToken = parseBearer(authorization);
        PartnerPrincipal principal = tokenService.authenticate(rawToken);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        return true;
    }

    private String parseBearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "missing Authorization header");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "unsupported authorization scheme");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new GatewayException(ErrorCode.UNAUTHENTICATED, "empty bearer token");
        }
        return token;
    }
}
