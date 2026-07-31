package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketTradeSample(
    UUID tradeId, UUID buyerAccountId, UUID sellerAccountId,
    BigDecimal price, long quantity, long matchSequence, Instant executedAt) {
  public MarketTradeSample {
    Objects.requireNonNull(tradeId, "tradeId");
    Objects.requireNonNull(buyerAccountId, "buyerAccountId");
    Objects.requireNonNull(sellerAccountId, "sellerAccountId");
    if (buyerAccountId.equals(sellerAccountId)
        || price == null || price.signum() <= 0 || quantity <= 0 || matchSequence <= 0
        || executedAt == null) {
      throw new IllegalArgumentException("invalid market trade sample");
    }
  }
}
