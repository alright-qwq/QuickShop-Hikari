package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.math.BigDecimal;
import java.sql.SQLException;
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
  void insertOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity)
      throws SQLException;
  void updateOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity,
                   long expectedVersion) throws SQLException;
  void insertTrade(Trade trade) throws SQLException;
}
