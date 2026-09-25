package com.github.highcumontoa.openbankinggatewayjava.store;

import com.github.highcumontoa.openbankinggatewayjava.domain.LedgerEntry;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Repository
public class InMemoryLedgerRepository implements LedgerRepository {

    private final List<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(LedgerEntry entry) {
        entries.add(entry);
    }

    @Override
    public List<LedgerEntry> findAll() {
        return List.copyOf(entries);
    }

    @Override
    public BigDecimal signedTotal() {
        return entries.stream()
                .map(e -> e.direction() == LedgerEntry.Direction.DEBIT
                        ? e.amount()
                        : e.amount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
