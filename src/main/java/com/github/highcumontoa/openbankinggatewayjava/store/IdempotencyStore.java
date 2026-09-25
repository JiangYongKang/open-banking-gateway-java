package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存幂等表。(partnerId, idempotencyKey) 为复合键，键维度串行化，
 * 保证同键并发请求只有一个进入业务执行，其余等待后回放同一结果。
 */
@Component
public class IdempotencyStore {

    public record Key(String partnerId, String idempotencyKey) {
    }

    private final ConcurrentMap<Key, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<Key, Object> locks = new ConcurrentHashMap<>();

    public Optional<IdempotencyRecord> find(String partnerId, String idempotencyKey) {
        return Optional.ofNullable(records.get(new Key(partnerId, idempotencyKey)));
    }

    public Object lockFor(String partnerId, String idempotencyKey) {
        return locks.computeIfAbsent(new Key(partnerId, idempotencyKey), k -> new Object());
    }

    public void put(IdempotencyRecord record) {
        records.put(new Key(record.partnerId(), record.idempotencyKey()), record);
    }
}
