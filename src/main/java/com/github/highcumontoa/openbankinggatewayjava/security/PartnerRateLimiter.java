package com.github.highcumontoa.openbankinggatewayjava.security;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 合作方维度滑动窗口限流：仅对支付提交计数。
 * 在窗口内达到阈值即拒绝，被拒绝的请求不计数（拒绝不得改变账户余额，也不消耗额度）。
 */
@Component
public class PartnerRateLimiter {

    private final GatewayProperties properties;
    private final ConcurrentMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public PartnerRateLimiter(GatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * @return true 放行并计入本次；false 触发限流
     */
    public boolean tryAcquire(String partnerId) {
        long now = System.nanoTime();
        long windowNanos = properties.getRateLimit().getWindow().toNanos();
        int limit = properties.getRateLimit().getRequestsPerWindow();

        Deque<Long> deque = windows.computeIfAbsent(partnerId, k -> new LinkedBlockingDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowNanos) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }
}
