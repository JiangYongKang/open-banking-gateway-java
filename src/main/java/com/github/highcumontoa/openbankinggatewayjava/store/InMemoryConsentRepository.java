package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryConsentRepository implements ConsentRepository {

    private final ConcurrentMap<String, Consent> consents = new ConcurrentHashMap<>();

    @Override
    public void save(Consent consent) {
        consents.put(consent.id(), consent);
    }

    @Override
    public Optional<Consent> findById(String consentId) {
        return Optional.ofNullable(consents.get(consentId));
    }

    @Override
    public List<Consent> findAll() {
        return List.copyOf(consents.values());
    }
}
