package com.github.highcumontoa.openbankinggatewayjava.payment;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储：以 (partnerId, idempotencyKey) 为复合键。
 * 同一复合键通过独立锁串行化“查重 -> 处理 -> 落记录”，保证并发重复提交只生效一次。
 */
@Component
public class IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    private static String composite(String partnerId, String key) {
        return partnerId + "::" + key;
    }

    public Object lockOf(String partnerId, String key) {
        return keyLocks.computeIfAbsent(composite(partnerId, key), k -> new Object());
    }

    public Optional<IdempotencyRecord> find(String partnerId, String key) {
        return Optional.ofNullable(records.get(composite(partnerId, key)));
    }

    public void save(IdempotencyRecord record) {
        records.put(composite(record.getPartnerId(), record.getIdempotencyKey()), record);
    }
}
