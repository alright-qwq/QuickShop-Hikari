package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable hard limits for participant-aware trusted price discovery. */
public record TrustedPricePolicy(
    Duration budgetWindow, Duration confidenceWindow, Map<LiquidityTier, Tier> tiers) {

  public TrustedPricePolicy {
    requirePositiveWindow(budgetWindow);
    requirePositiveWindow(confidenceWindow);
    Objects.requireNonNull(tiers, "tiers");
    EnumMap<LiquidityTier, Tier> copy = new EnumMap<>(LiquidityTier.class);
    copy.putAll(tiers);
    for (LiquidityTier tier : LiquidityTier.values()) {
      if (copy.get(tier) == null) {
        throw new IllegalArgumentException("trusted price tier is missing: " + tier);
      }
    }
    if (copy.size() != LiquidityTier.values().length) {
      throw new IllegalArgumentException("unknown trusted price tier");
    }
    tiers = Map.copyOf(copy);
  }

  public static TrustedPricePolicy defaults() {
    return new TrustedPricePolicy(Duration.ofHours(6), Duration.ofHours(24), Map.of(
        LiquidityTier.LOW, new Tier(
            decimal("0.005"), decimal("0.030"), decimal("0.015"), decimal("0.0075"),
            decimal("0.10"), decimal("0.005")),
        LiquidityTier.GROWING, new Tier(
            decimal("0.015"), decimal("0.080"), decimal("0.040"), decimal("0.020"),
            decimal("0.25"), decimal("0.0015")),
        LiquidityTier.STABLE, new Tier(
            decimal("0.030"), decimal("0.200"), decimal("0.080"), decimal("0.040"),
            decimal("0.60"), BigDecimal.ZERO)));
  }

  public Tier tier(LiquidityTier tier) {
    return tiers.get(Objects.requireNonNull(tier, "tier"));
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }

  private static void requirePositiveWindow(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("trusted price window must be positive");
    }
  }

  public record Tier(
      BigDecimal perTradeCap, BigDecimal marketBudget, BigDecimal accountBudget,
      BigDecimal pairBudget, BigDecimal anchorBand, BigDecimal reversionPerHour) {

    public Tier {
      requireRatio(perTradeCap, "per-trade cap");
      requireRatio(marketBudget, "market budget");
      requireRatio(accountBudget, "account budget");
      requireRatio(pairBudget, "pair budget");
      requireRatio(anchorBand, "anchor band");
      requireRatio(reversionPerHour, "reversion rate");
      if (pairBudget.compareTo(accountBudget) > 0
          || accountBudget.compareTo(marketBudget) > 0
          || perTradeCap.compareTo(marketBudget) > 0) {
        throw new IllegalArgumentException("trusted price budgets are inconsistent");
      }
    }

    private static void requireRatio(BigDecimal value, String name) {
      if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
        throw new IllegalArgumentException(name + " must be within 0..1");
      }
    }
  }
}
