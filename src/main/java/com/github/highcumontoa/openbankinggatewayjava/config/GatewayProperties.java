package com.github.highcumontoa.openbankinggatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关可配置项：同意书有效期、合作方维度限流（令牌桶）。
 *
 * 配置前缀 obg.gateway
 */
@ConfigurationProperties(prefix = "obg.gateway")
public class GatewayProperties {

    /** 同意书默认有效期（秒）。 */
    private long consentTtlSeconds = 3600L;

    /** 每个合作方每分钟允许的支付请求数（令牌桶补充速率）。 */
    private int rateLimitPermitsPerMinute = 20;

    /** 每个合作方的令牌桶容量（可容纳的突发请求数）。 */
    private int rateLimitBurstCapacity = 20;

    public long getConsentTtlSeconds() {
        return consentTtlSeconds;
    }

    public void setConsentTtlSeconds(long consentTtlSeconds) {
        this.consentTtlSeconds = consentTtlSeconds;
    }

    public int getRateLimitPermitsPerMinute() {
        return rateLimitPermitsPerMinute;
    }

    public void setRateLimitPermitsPerMinute(int rateLimitPermitsPerMinute) {
        this.rateLimitPermitsPerMinute = rateLimitPermitsPerMinute;
    }

    public int getRateLimitBurstCapacity() {
        return rateLimitBurstCapacity;
    }

    public void setRateLimitBurstCapacity(int rateLimitBurstCapacity) {
        this.rateLimitBurstCapacity = rateLimitBurstCapacity;
    }
}
