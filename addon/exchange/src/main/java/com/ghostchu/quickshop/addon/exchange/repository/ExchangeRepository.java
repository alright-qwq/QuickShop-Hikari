package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.display.TrustedPricePoint;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;

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

  default List<TrustedPricePoint> loadTrustedPricePoints(
      String marketId, Instant fromInclusive, Instant toExclusive) throws SQLException {
    throw new UnsupportedOperationException("trusted chart points are not supported by this repository");
  }

  default Optional<Candle> latestCandle(String marketId, Instant beforeExclusive)
      throws SQLException {
    List<Candle> candles = loadCandles(marketId, Instant.EPOCH, beforeExclusive);
    return candles.isEmpty() ? Optional.empty() : Optional.of(candles.getLast());
  }

  default List<AuditRecord> auditRecords(Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    throw new UnsupportedOperationException("audit records are not supported by this repository");
  }

  /** Reads a bounded page of a player's currently cancellable orders. */
  default List<ExchangeTransaction.PersistedOrder> accountOpenOrders(
      UUID accountId, int limit, int offset) throws SQLException {
    throw new UnsupportedOperationException("account order reads are not supported by this repository");
  }

  default List<AccountAssetBalance> accountAssets(UUID accountId) throws SQLException {
    throw new UnsupportedOperationException("account asset reads are not supported by this repository");
  }

  default List<Trade> accountTrades(UUID accountId, int limit, int offset) throws SQLException {
    throw new UnsupportedOperationException("account trade reads are not supported by this repository");
  }

  default List<TransferRecord> accountTransfers(UUID accountId, int limit, int offset)
      throws SQLException {
    throw new UnsupportedOperationException("account transfer reads are not supported by this repository");
  }

  default List<AccountLedgerEntry> accountLedgerEntries(
      UUID accountId, int limit, int offset) throws SQLException {
    throw new UnsupportedOperationException("account ledger reads are not supported by this repository");
  }

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
