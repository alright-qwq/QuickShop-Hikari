package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;

import java.math.BigDecimal;

public final class ReservationCalculator {
  private final FeeCalculator fees;

  public ReservationCalculator(FeeCalculator fees) {
    if (fees == null) {
      throw new IllegalArgumentException("fee calculator is required");
    }
    this.fees = fees;
  }

  public Reservation reserve(Order order, MarketRules rules) {
    if (order == null || rules == null) {
      throw new IllegalArgumentException("order and market rules are required");
    }
    if (!rules.marketId().equals(order.marketId())) {
      throw new IllegalArgumentException("order market does not match rules");
    }
    if (order.side() == OrderSide.SELL) {
      return new Reservation(BigDecimal.ZERO, order.remainingQuantity());
    }
    BigDecimal maximumPrice = order.type() == OrderType.LIMIT
        ? order.limitPrice() : order.slippageBoundary();
    BigDecimal notional = maximumPrice.multiply(BigDecimal.valueOf(order.remainingQuantity()));
    return new Reservation(notional.add(fees.fee(notional, rules.takerFeeRate())), 0);
  }
}
