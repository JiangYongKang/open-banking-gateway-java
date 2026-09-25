package com.github.highcumontoa.openbankinggatewayjava.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时钟：默认 UTC 系统时钟；测试可替换为固定/可控时钟以验证过期行为。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
