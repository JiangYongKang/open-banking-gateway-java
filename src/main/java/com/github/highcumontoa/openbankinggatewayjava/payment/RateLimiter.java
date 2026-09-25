package com.github.highcumontoa.openbankinggatewayjava.payment;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合作方维度令牌桶限流（线程安全）。
 * 每个 partnerId 独立桶：容量 burstCapacity，按 permitsPerMinute 速率惰性补充。
 * 被限流的请求在业务处理之前拒绝，绝不触碰余额与账本。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final GatewayProperties properties;
    private final Clock clock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public boolean tryAcquire(String partnerId) {
        TokenBucket bucket = buckets.computeIfAbsent(partnerId,
                k -> new TokenBucket(properties.getRateLimitBurstCapacity()));
        boolean allowed = bucket.tryConsume();
        if (!allowed) {
            log.warn("rate-limit | partner={} | verdict=DENY | tokens={} | capacity={} | permitsPerMinute={}",
                    partnerId, bucket.tokens, properties.getRateLimitBurstCapacity(),
                    properties.getRateLimitPermitsPerMinute());
        }
        return allowed;
    }

    /** 供测试重置。 */
    public void reset() {
        buckets.clear();
    }

    private final class TokenBucket {
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0d) {
                tokens -= 1.0d;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedMinutes = (now - lastRefillNanos) / 60_000_000_000.0d;
            double refill = elapsedMinutes * properties.getRateLimitPermitsPerMinute();
            if (refill > 0.0d) {
                tokens = Math.min(properties.getRateLimitBurstCapacity(), tokens + refill);
                lastRefillNanos = now;
            }
        }
    }
}
