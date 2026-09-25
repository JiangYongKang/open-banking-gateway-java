package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;

import java.util.List;

public interface PaymentRepository {

    void save(Payment payment);

    List<Payment> findAll();
}
