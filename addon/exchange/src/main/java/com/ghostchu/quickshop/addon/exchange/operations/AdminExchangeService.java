package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates audited administration through the market services that own live order books. */
public final class AdminExchangeService {
  private final ExchangeRepository repository;
  private final Map<String, PersistentOrderService> markets;

  public AdminExchangeService(Map<String, PersistentOrderService> markets) {
    this(null, markets);
  }

  public AdminExchangeService(ExchangeRepository repository,
                              Map<String, PersistentOrderService> markets) {
    this.repository = repository;
    this.markets = Map.copyOf(Objects.requireNonNull(markets, "markets"));
  }

  public void pauseMarket(UUID actorId, String marketId, String reason) throws SQLException {
    changeMarketStatus(actorId, marketId, reason, MarketStatus.OPEN, MarketStatus.PAUSED,
        "PAUSE_MARKET");
  }

  public void resumeMarket(UUID actorId, String marketId, String reason) throws SQLException {
    changeMarketStatus(actorId, marketId, reason, MarketStatus.PAUSED, MarketStatus.OPEN,
        "RESUME_MARKET");
  }

  public OrderReceipt forceCancel(UUID actorId, UUID requestId, String marketId, UUID orderId,
                                  String reason) throws SQLException {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    PersistentOrderService market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return market.forceCancel(actorId, requestId, orderId, reason);
  }

  /** Locates an active order across configured markets without exposing market selection to staff. */
  public OrderReceipt forceCancel(UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    Objects.requireNonNull(orderId, "orderId");
    IllegalArgumentException missing = null;
    for (PersistentOrderService market : markets.values()) {
      try {
        return market.forceCancel(actorId, requestId, orderId, reason);
      } catch (IllegalArgumentException failure) {
        if (!failure.getMessage().startsWith("order is not open:")) {
          throw failure;
        }
        missing = failure;
      }
    }
    throw missing == null ? new IllegalArgumentException("order is not open: " + orderId) : missing;
  }

  private void changeMarketStatus(UUID actorId, String marketId, String reason,
                                  MarketStatus expected, MarketStatus target, String action)
      throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    if (marketId == null || marketId.isBlank() || !markets.containsKey(marketId)) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    String normalizedReason = normalizeReason(reason);
    if (repository == null) {
      throw new IllegalStateException("market administration requires a repository");
    }
    repository.inTransaction(tx -> {
      MarketState before = tx.marketState(marketId);
      if (before.status() != expected) {
        throw new IllegalStateException("market must be " + expected + " before " + action);
      }
      MarketState after = new MarketState(before.marketId(), target, before.prioritySequence(),
          before.matchSequence(), before.referencePrice(), before.lastPrice(), null,
          before.discoveryQuantity(), before.circuitBreakerLevel(), before.version() + 1);
      tx.updateMarketState(after, before.version());
      tx.appendAudit(new AuditRecord(UUID.randomUUID(), actorId, action, marketId,
          normalizedReason, "status=" + before.status(), "status=" + target, Instant.now()));
      return null;
    });
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException("administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }
}
