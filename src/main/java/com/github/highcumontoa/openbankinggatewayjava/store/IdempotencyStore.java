package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.config.GatewayProperties;
import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存幂等/回查表。(partnerId, idempotencyKey) 为复合键，键维度串行化，
 * 保证同键并发请求只有一个进入业务执行，其余等待后回放同一结果。
 *
 * 记录有保留窗口（obg.idempotency.retention）：超窗记录在访问时被惰性清理，
 * 清理只影响回查能力，不影响已生效的支付与账本。
 * 键锁使用固定条带（striped）数组，锁数量有界，不随键数增长。
 */
@Component
public class IdempotencyStore {

    public record Key(String partnerId, String idempotencyKey) {
    }

    private static final int LOCK_STRIPES = 256;

    private final ConcurrentMap<Key, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Object[] locks = new Object[LOCK_STRIPES];
    private final GatewayProperties properties;

    public IdempotencyStore(GatewayProperties properties) {
        this.properties = properties;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * 查找键对应记录；超窗记录视为不存在并顺手清理。
     */
    public Optional<IdempotencyRecord> find(String partnerId, String idempotencyKey) {
        Key key = new Key(partnerId, idempotencyKey);
        IdempotencyRecord record = records.get(key);
        if (record == null) {
            return Optional.empty();
        }
        if (isExpired(record, Instant.now())) {
            records.remove(key, record);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public Object lockFor(String partnerId, String idempotencyKey) {
        return locks[Math.floorMod(new Key(partnerId, idempotencyKey).hashCode(), LOCK_STRIPES)];
    }

    public void put(IdempotencyRecord record) {
        purgeExpired(Instant.now());
        records.put(new Key(record.partnerId(), record.idempotencyKey()), record);
    }

    /** 清理全部超窗记录；窗口内记录不受影响。 */
    public int purgeExpired(Instant now) {
        int removed = 0;
        for (var entry : records.entrySet()) {
            if (isExpired(entry.getValue(), now) && records.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private boolean isExpired(IdempotencyRecord record, Instant now) {
        return record.createdAt().plus(properties.getIdempotency().getRetention()).isBefore(now);
    }
}
