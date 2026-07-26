package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Trade(
    UUID tradeId, String marketId, UUID makerOrderId, UUID takerOrderId,
    UUID buyerAccountId, UUID sellerAccountId, BigDecimal price, long quantity,
    BigDecimal makerFee, BigDecimal takerFee, long matchSequence, Instant executedAt) {
  public Trade {
    if (tradeId == null || quantity <= 0 || price == null || price.signum() <= 0) {
      throw new IllegalArgumentException("invalid trade");
    }
  }
}
