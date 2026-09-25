package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import jakarta.servlet.http.HttpServletRequest;
import com.github.highcumontoa.openbankinggatewayjava.domain.CallerContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 对 /api/** 强制令牌认证；校验逻辑与错误码全部由 TokenService 决定，
 * 保证"无令牌 / 伪令牌 / 过期令牌"对外错误语义一致、稳定。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    public AuthenticationInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CallerContext context = tokenService.authenticate(request.getHeader("Authorization"));
        request.setAttribute(CallerHolder.REQUEST_ATTRIBUTE, context);
        return true;
    }
}
