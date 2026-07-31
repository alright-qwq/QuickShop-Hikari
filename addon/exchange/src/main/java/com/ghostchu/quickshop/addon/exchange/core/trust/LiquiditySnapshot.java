package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Participation metrics used to select a trusted-price influence tier. */
public record LiquiditySnapshot(
    LiquidityTier tier, Set<UUID> participantIds, Set<String> pairKeys,
    int effectiveTrades, int activeBuckets,
    BigDecimal accountConcentration, BigDecimal pairConcentration) {

  public LiquiditySnapshot {
    Objects.requireNonNull(tier, "tier");
    participantIds = Set.copyOf(Objects.requireNonNull(participantIds, "participantIds"));
    pairKeys = Set.copyOf(Objects.requireNonNull(pairKeys, "pairKeys"));
    requireRatio(accountConcentration, "account concentration");
    requireRatio(pairConcentration, "pair concentration");
    if (effectiveTrades < 0 || activeBuckets < 0) {
      throw new IllegalArgumentException("liquidity counts must not be negative");
    }
  }

  public int participants() {
    return participantIds.size();
  }

  public int pairs() {
    return pairKeys.size();
  }

  private static void requireRatio(BigDecimal value, String name) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be within 0..1");
    }
  }
}
