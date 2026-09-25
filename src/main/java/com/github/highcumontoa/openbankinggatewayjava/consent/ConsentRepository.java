package com.github.highcumontoa.openbankinggatewayjava.consent;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同意书内存仓储。
 */
@Repository
public class ConsentRepository {

    private final Map<String, Consent> consents = new ConcurrentHashMap<>();

    public Consent save(Consent consent) {
        consents.put(consent.getId(), consent);
        return consent;
    }

    public Optional<Consent> findById(String id) {
        return Optional.ofNullable(consents.get(id));
    }
}
