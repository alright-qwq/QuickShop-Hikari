package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MarketRules(
    String marketId, String currencyId, BigDecimal basePrice,
    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal tickSize,
    long minQuantity, long maxQuantity, int priceScale,
    BigDecimal makerFeeRate, BigDecimal takerFeeRate) {

  public MarketRules {
    if (marketId == null || marketId.isBlank() || currencyId == null || currencyId.isBlank()) {
      throw new IllegalArgumentException("market and currency are required");
    }
    if (minQuantity <= 0 || maxQuantity < minQuantity || priceScale < 0) {
      throw new IllegalArgumentException("invalid quantity or scale");
    }
    requirePositive(basePrice, "basePrice");
    requirePositive(minPrice, "minPrice");
    requirePositive(maxPrice, "maxPrice");
    requirePositive(tickSize, "tickSize");
    if (minPrice.compareTo(maxPrice) >= 0) {
      throw new IllegalArgumentException("minPrice must be below maxPrice");
    }
    validateRate(makerFeeRate);
    validateRate(takerFeeRate);
  }

  public void validatePrice(BigDecimal price) {
    if (price == null || price.scale() > priceScale
        || price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
      throw new IllegalArgumentException("price outside market bounds");
    }
    BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.DOWN);
    if (ticks.multiply(tickSize).compareTo(price) != 0) {
      throw new IllegalArgumentException("price is not aligned to tickSize");
    }
  }

  public void validateQuantity(long quantity) {
    if (quantity < minQuantity || quantity > maxQuantity) {
      throw new IllegalArgumentException("quantity outside market bounds");
    }
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
  }

  private static void validateRate(BigDecimal rate) {
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("fee rate outside 0..1");
    }
  }
}
