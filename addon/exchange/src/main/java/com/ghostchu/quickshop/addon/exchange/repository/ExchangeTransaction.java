package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeTransaction {
  CurrencyBalance currency(UUID accountId, String currencyId) throws SQLException;
  ItemBalance inventory(UUID accountId, String marketId) throws SQLException;
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
  List<PersistedOrder> openOrders(String marketId) throws SQLException;
  void updateMarketState(MarketState state, long expectedVersion) throws SQLException;
  void insertHighAlert(UUID alertId, String marketId, String alertType,
                       String payload, Instant createdAt) throws SQLException;
  void insertOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity)
      throws SQLException;
  void updateOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity,
                   long expectedVersion) throws SQLException;
  void insertTrade(Trade trade) throws SQLException;
  void appendJournal(LedgerJournal journal) throws SQLException;

  record PersistedOrder(Order order, BigDecimal reservedCurrency,
                        long reservedQuantity, long version) {}

  record MarketState(String marketId, MarketStatus status, long prioritySequence,
                     long matchSequence, BigDecimal referencePrice, BigDecimal lastPrice,
                     Instant haltedUntil, Long discoveryQuantity,
                     Integer circuitBreakerLevel, long version) {}
}
