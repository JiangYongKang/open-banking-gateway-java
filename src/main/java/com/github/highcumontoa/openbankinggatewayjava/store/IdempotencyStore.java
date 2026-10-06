package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.IdempotencyRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存幂等表。(partnerId, idempotencyKey) 为复合键，键维度串行化，
 * 保证同键并发请求只有一个进入业务执行，其余等待后回放同一结果。
 * 记录有保留期：超窗记录由 {@link #purgeExpired(Instant)} 清理，
 * 清理只移除回查入口，不影响已生效的支付与账务记录。
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

    /**
     * 清理创建时间早于 cutoff 的记录。在键锁内摘除，避免与在途请求交错；
     * 窗口内的记录原样保留。返回清理条数。
     */
    public int purgeExpired(Instant cutoff) {
        List<Key> expired = new ArrayList<>();
        records.forEach((key, record) -> {
            if (record.createdAt().isBefore(cutoff)) {
                expired.add(key);
            }
        });
        int purged = 0;
        for (Key key : expired) {
            Object lock = locks.computeIfAbsent(key, k -> new Object());
            synchronized (lock) {
                IdempotencyRecord record = records.get(key);
                if (record != null && record.createdAt().isBefore(cutoff)) {
                    records.remove(key);
                    locks.remove(key, lock);
                    purged++;
                }
            }
        }
        return purged;
    }

    /** 当前记录数（测试与监控用）。 */
    public int size() {
        return records.size();
    }
}
