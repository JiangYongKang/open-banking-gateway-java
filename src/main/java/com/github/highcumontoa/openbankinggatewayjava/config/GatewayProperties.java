package com.github.highcumontoa.openbankinggatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 网关可配置项：
 * <ul>
 *   <li>obg.consent.default-ttl：同意书默认有效期</li>
 *   <li>obg.rate-limit.requests-per-window：合作方每窗口允许的支付请求数</li>
 *   <li>obg.rate-limit.window：限流窗口长度</li>
 *   <li>obg.currency：允许的支付币种</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "obg")
public class GatewayProperties {

    private final Consent consent = new Consent();
    private final RateLimit rateLimit = new RateLimit();
    private String currency = "CNY";

    public Consent getConsent() {
        return consent;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public static class Consent {
        private Duration defaultTtl = Duration.ofHours(1);

        public Duration getDefaultTtl() {
            return defaultTtl;
        }

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
        }
    }

    public static class RateLimit {
        private int requestsPerWindow = 10;
        private Duration window = Duration.ofMinutes(1);

        public int getRequestsPerWindow() {
            return requestsPerWindow;
        }

        public void setRequestsPerWindow(int requestsPerWindow) {
            this.requestsPerWindow = requestsPerWindow;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
