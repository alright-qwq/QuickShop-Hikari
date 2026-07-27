package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import java.sql.SQLException;

public interface ExchangeRepository {
  <T> T inTransaction(TransactionWork<T> work) throws SQLException;

  /** Identity shared by repository decorators that coordinate access to the same database. */
  default Object coordinationKey() {
    return this;
  }

  default ReconciliationReport reconcile() throws SQLException {
    throw new UnsupportedOperationException("reconciliation is not supported by this repository");
  }

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
