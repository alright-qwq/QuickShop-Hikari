package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** Deliberately permissive thresholds for progressive pair-level behavior handling. */
public record BehaviorRiskPolicy(
    Duration window,
    int observePairTrades,
    int alertPairTrades,
    int cooldownPairTrades,
    BigDecimal alertPairConcentration,
    BigDecimal cooldownPairConcentration,
    Duration sustainedSpan,
    BigDecimal directionalPressure,
    Duration cooldown) {

  public BehaviorRiskPolicy {
    requirePositive(window, "window");
    requirePositive(sustainedSpan, "sustained span");
    requirePositive(cooldown, "cooldown");
    if (observePairTrades <= 0
        || alertPairTrades < observePairTrades
        || cooldownPairTrades < alertPairTrades) {
      throw new IllegalArgumentException("pair trade thresholds are invalid");
    }
    requireRatio(alertPairConcentration, "alert pair concentration");
    requireRatio(cooldownPairConcentration, "cooldown pair concentration");
    requireRatio(directionalPressure, "directional pressure");
    if (cooldownPairConcentration.compareTo(alertPairConcentration) < 0
        || sustainedSpan.compareTo(window) > 0) {
      throw new IllegalArgumentException("behavior risk thresholds are inconsistent");
    }
  }

  public static BehaviorRiskPolicy defaults() {
    return new BehaviorRiskPolicy(
        Duration.ofHours(2),
        6,
        16,
        24,
        new BigDecimal("0.70"),
        new BigDecimal("0.85"),
        Duration.ofMinutes(20),
        new BigDecimal("0.20"),
        Duration.ofMinutes(5));
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireRatio(BigDecimal value, String name) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be within 0..1");
    }
  }
}
