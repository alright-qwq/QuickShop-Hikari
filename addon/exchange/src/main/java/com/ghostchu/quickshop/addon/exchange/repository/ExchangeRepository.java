package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.math.BigDecimal;

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

  /**
   * Reads the most recent trades for a market, newest first, including the taker side so
   * market detail pages can show the aggressive direction.
   */
  default List<MarketTradeRow> marketTrades(String marketId, int limit) throws SQLException {
    throw new UnsupportedOperationException("market trade reads are not supported by this repository");
  }

  /** 24h market trade summary used by the market detail page. */
  default MarketTradeSummary marketTradeSummary(String marketId, Instant sinceInclusive)
      throws SQLException {
    throw new UnsupportedOperationException("market trade reads are not supported by this repository");
  }

  record MarketTradeSummary(int tradeCount, int buyCount, int sellCount, long volume) {
    public MarketTradeSummary {
      if (tradeCount < 0 || buyCount < 0 || sellCount < 0 || volume < 0) {
        throw new IllegalArgumentException("trade summary must be non-negative");
      }
    }
  }

  /** Lightweight market-detail read model for one recent trade. */
  record MarketTradeRow(BigDecimal price, long quantity, OrderSide takerSide,
                        Instant executedAt) {
    public MarketTradeRow {
      if (price == null || price.signum() <= 0 || quantity <= 0 || takerSide == null
          || executedAt == null) {
        throw new IllegalArgumentException("invalid market trade row");
      }
    }
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
