package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Consent;

import java.util.List;
import java.util.Optional;

public interface ConsentRepository {

    void save(Consent consent);

    Optional<Consent> findById(String consentId);

    List<Consent> findAll();
}
