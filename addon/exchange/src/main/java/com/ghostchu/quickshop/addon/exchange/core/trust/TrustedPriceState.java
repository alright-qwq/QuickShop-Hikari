package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Durable per-market trusted and administrator-guided price state. */
public record TrustedPriceState(
    String marketId, BigDecimal trustedPrice, BigDecimal guidancePrice,
    Instant lastEvaluatedAt, LiquidityTier liquidityTier,
    long policyVersion, long lastMatchSequence, long stateVersion) {

  public TrustedPriceState {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    requirePositive(trustedPrice, "trusted price");
    requirePositive(guidancePrice, "guidance price");
    Objects.requireNonNull(lastEvaluatedAt, "lastEvaluatedAt");
    Objects.requireNonNull(liquidityTier, "liquidityTier");
    if (policyVersion <= 0 || lastMatchSequence < 0 || stateVersion < 0) {
      throw new IllegalArgumentException("trusted price state version is invalid");
    }
  }

  public TrustedPriceState withLiquidityTier(LiquidityTier tier) {
    return new TrustedPriceState(marketId, trustedPrice, guidancePrice, lastEvaluatedAt,
        Objects.requireNonNull(tier, "tier"), policyVersion, lastMatchSequence, stateVersion);
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
