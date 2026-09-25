package com.github.highcumontoa.openbankinggatewayjava.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册安全拦截器，覆盖全部 /api/** 接口。
 */
@Configuration
public class SecurityWebConfig implements WebMvcConfigurer {

    private final SecurityInterceptor securityInterceptor;

    public SecurityWebConfig(SecurityInterceptor securityInterceptor) {
        this.securityInterceptor = securityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor).addPathPatterns("/api/**");
    }
}
