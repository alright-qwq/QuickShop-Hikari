package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceAdjustment;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeTransaction {
  CurrencyBalance currency(UUID accountId, String currencyId) throws SQLException;
  ItemBalance inventory(UUID accountId, String marketId) throws SQLException;
  Optional<CurrencyBalance> existingCurrency(UUID accountId, String currencyId)
      throws SQLException;
  Optional<ItemBalance> existingInventory(UUID accountId, String marketId) throws SQLException;
  void creditAvailableCurrency(UUID accountId, String currencyId, BigDecimal amount)
      throws SQLException;
  void freezeCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void releaseCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void consumeFrozenCurrency(UUID accountId, String currencyId, BigDecimal amount)
      throws SQLException;
  void creditAvailableItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void freezeItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void releaseItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void consumeFrozenItems(UUID accountId, String marketId, long quantity) throws SQLException;
  Optional<StoredRequestResult> requestResult(UUID accountId, UUID requestId) throws SQLException;
  void putRequestResult(StoredRequestResult result) throws SQLException;
  MarketState marketState(String marketId) throws SQLException;
  long marketStructuralVersion(String marketId) throws SQLException;
  MarketFeeSchedule marketFeeSchedule(String marketId) throws SQLException;
  MarketSnapshot marketSnapshot(MarketState state, Instant cutoff) throws SQLException;
  default TrustedMarketSnapshot trustedMarketSnapshot(
      String marketId, Instant budgetCutoff, Instant confidenceCutoff) throws SQLException {
    throw new UnsupportedOperationException("trusted market snapshots are not supported");
  }
  void visitTradeHistory(String marketId, TradeVisitor visitor) throws SQLException;
  List<PersistedOrder> openOrders(String marketId) throws SQLException;
  void updateMarketState(MarketState state, long expectedVersion) throws SQLException;
  void insertHighAlert(UUID alertId, String marketId, String alertType,
                       String payload, Instant createdAt) throws SQLException;
  void appendAudit(AuditRecord record) throws SQLException;
  void insertOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity)
      throws SQLException;
  void updateOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity,
                   long expectedVersion) throws SQLException;
  void insertTrade(Trade trade) throws SQLException;
  default void insertTradeInfluence(TradeInfluence influence) throws SQLException {
    throw new UnsupportedOperationException("trusted market influence is not supported");
  }
  default void insertTrustedAdjustment(TrustedPriceAdjustment adjustment) throws SQLException {
    throw new UnsupportedOperationException("trusted market adjustments are not supported");
  }
  default void updateTrustedPriceState(TrustedPriceState state, long expectedVersion)
      throws SQLException {
    throw new UnsupportedOperationException("trusted market state is not supported");
  }
  void upsertCandle(Candle candle) throws SQLException;
  void appendJournal(LedgerJournal journal) throws SQLException;
  default ReconciliationReport reconcile() throws SQLException {
    throw new UnsupportedOperationException("reconciliation is not supported by this transaction");
  }
  TransferRecord createTransfer(TransferRecord prepared) throws SQLException;
  Optional<TransferRecord> transfer(UUID transferId) throws SQLException;
  TransferRecord completeTransfer(UUID transferId, long expectedVersion) throws SQLException;
  TransferRecord failTransfer(UUID transferId, long expectedVersion, String reason)
      throws SQLException;
  TransferRecord resolveReviewedTransfer(
      UUID transferId, long expectedVersion,
      com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus targetStatus,
      String reason) throws SQLException;

  record PersistedOrder(Order order, BigDecimal reservedCurrency,
                        long reservedQuantity, long version) {}

  record MarketState(String marketId, MarketStatus status, long prioritySequence,
                     long matchSequence, BigDecimal referencePrice, BigDecimal lastPrice,
                     Instant haltedUntil, Long discoveryQuantity,
                     Integer circuitBreakerLevel, long version) {}

  @FunctionalInterface
  interface TradeVisitor {
    void accept(MarketTradeSample sample) throws SQLException;
  }
}
