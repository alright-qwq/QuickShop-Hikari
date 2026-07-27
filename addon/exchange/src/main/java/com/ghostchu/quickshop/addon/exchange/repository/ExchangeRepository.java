package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRepository {
  <T> T inTransaction(TransactionWork<T> work) throws SQLException;

  /** Identity shared by repository decorators that coordinate access to the same database. */
  default Object coordinationKey() {
    return this;
  }

  /**
   * Reads a durable request receipt without taking a market settlement transaction.
   * Repository decorators that cannot provide a separate read connection may use the
   * transactional fallback.
   */
  default Optional<StoredRequestResult> findRequestResult(UUID accountId, UUID requestId)
      throws SQLException {
    return inTransaction(transaction -> transaction.requestResult(accountId, requestId));
  }

  default ReconciliationReport reconcile() throws SQLException {
    throw new UnsupportedOperationException("reconciliation is not supported by this repository");
  }

  default void upsertCandle(Candle candle) throws SQLException {
    throw new UnsupportedOperationException("candle persistence is not supported by this repository");
  }

  default List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    throw new UnsupportedOperationException("candle persistence is not supported by this repository");
  }

  default List<AuditRecord> auditRecords(Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    throw new UnsupportedOperationException("audit records are not supported by this repository");
  }

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
