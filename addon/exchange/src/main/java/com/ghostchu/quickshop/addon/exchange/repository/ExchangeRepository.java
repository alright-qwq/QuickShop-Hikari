package com.ghostchu.quickshop.addon.exchange.repository;

import java.sql.SQLException;

public interface ExchangeRepository {
  <T> T inTransaction(TransactionWork<T> work) throws SQLException;

  /** Identity shared by repository decorators that coordinate access to the same database. */
  default Object coordinationKey() {
    return this;
  }

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
