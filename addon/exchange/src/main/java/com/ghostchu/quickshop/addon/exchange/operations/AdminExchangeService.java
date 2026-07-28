package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates audited administration through the market services that own live order books. */
public final class AdminExchangeService {
  private final Map<String, PersistentOrderService> markets;

  public AdminExchangeService(Map<String, PersistentOrderService> markets) {
    this.markets = Map.copyOf(Objects.requireNonNull(markets, "markets"));
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
}
