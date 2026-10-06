package com.github.highcumontoa.openbankinggatewayjava.service;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.store.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 幂等回查记录的定期清理：按 obg.idempotency.retention 保留窗口，
 * 超窗记录被移除。清理只摘除回查入口，不触碰已生效的支付与账务记录。
 */
@Component
public class IdempotencyPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeScheduler.class);

    private final IdempotencyStore store;
    private final GatewayProperties properties;

    public IdempotencyPurgeScheduler(IdempotencyStore store, GatewayProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{@gatewayProperties.idempotency.purgeInterval.toMillis()}")
    public void purge() {
        Instant cutoff = Instant.now().minus(properties.getIdempotency().getRetention());
        int purged = store.purgeExpired(cutoff);
        if (purged > 0) {
            log.info("idempotency-purge purged={} cutoff={} remaining={}", purged, cutoff, store.size());
        }
    }
}
