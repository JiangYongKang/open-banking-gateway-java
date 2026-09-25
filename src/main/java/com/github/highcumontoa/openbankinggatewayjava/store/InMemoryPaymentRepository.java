package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.Payment;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final List<Payment> payments = new CopyOnWriteArrayList<>();

    @Override
    public void save(Payment payment) {
        payments.add(payment);
    }

    @Override
    public List<Payment> findAll() {
        return List.copyOf(payments);
    }
}
