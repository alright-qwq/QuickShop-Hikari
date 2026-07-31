package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit record for a trusted-price change that was not caused by a trade. */
public record TrustedPriceAdjustment(
    UUID adjustmentId, String marketId, AdjustmentType type,
    BigDecimal trustedPriceBefore, BigDecimal trustedPriceAfter,
    BigDecimal guidancePriceBefore, BigDecimal guidancePriceAfter,
    UUID actorId, String reason, long policyVersion, Instant adjustedAt) {

  public TrustedPriceAdjustment {
    Objects.requireNonNull(adjustmentId, "adjustmentId");
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    Objects.requireNonNull(type, "type");
    requirePositive(trustedPriceBefore, "trusted price before");
    requirePositive(trustedPriceAfter, "trusted price after");
    requirePositive(guidancePriceBefore, "guidance price before");
    requirePositive(guidancePriceAfter, "guidance price after");
    if (reason == null || reason.isBlank() || policyVersion <= 0) {
      throw new IllegalArgumentException("trusted price adjustment values are invalid");
    }
    Objects.requireNonNull(adjustedAt, "adjustedAt");
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
