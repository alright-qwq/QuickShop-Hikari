package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID orderId, UUID requestId, String marketId, UUID accountId,
    OrderSide side, OrderType type, TimeInForce timeInForce,
    BigDecimal limitPrice, BigDecimal slippageBoundary,
    long originalQuantity, long remainingQuantity, OrderStatus status,
    long prioritySequence, long configVersion, long feeVersion,
    Instant createdAt, Instant updatedAt) {

  public Order {
    if (orderId == null || requestId == null || accountId == null || marketId == null) {
      throw new IllegalArgumentException("order identity is required");
    }
    if (originalQuantity <= 0 || remainingQuantity < 0 || remainingQuantity > originalQuantity) {
      throw new IllegalArgumentException("invalid remaining quantity");
    }
    if (type == OrderType.LIMIT && (limitPrice == null || timeInForce != TimeInForce.GTC)) {
      throw new IllegalArgumentException("limit order requires price and GTC");
    }
    if (type == OrderType.MARKET && (slippageBoundary == null || timeInForce != TimeInForce.IOC)) {
      throw new IllegalArgumentException("market order requires IOC");
    }
  }

  public Order withRemaining(long remaining, Instant now) {
    OrderStatus next = remaining == 0 ? OrderStatus.FILLED
        : remaining == originalQuantity ? OrderStatus.OPEN : OrderStatus.PARTIALLY_FILLED;
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remaining, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }

  public Order withStatus(OrderStatus next, Instant now) {
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remainingQuantity, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }
}
