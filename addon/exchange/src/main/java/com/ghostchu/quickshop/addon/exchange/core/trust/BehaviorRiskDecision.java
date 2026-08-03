package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable explanation of a progressive pair-level behavior assessment. */
public record BehaviorRiskDecision(
    String marketId,
    String pairKey,
    BehaviorRiskAction action,
    Set<BehaviorRiskEvidence> evidence,
    int pairTrades,
    int marketTrades,
    BigDecimal pairConcentration,
    BigDecimal directionalPressure,
    Optional<Instant> cooldownUntil) {

  public BehaviorRiskDecision {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    if (pairKey == null || pairKey.isBlank()) {
      throw new IllegalArgumentException("pair key is required");
    }
    Objects.requireNonNull(action, "action");
    evidence = Set.copyOf(Objects.requireNonNull(evidence, "evidence"));
    if (pairTrades < 0 || marketTrades < pairTrades) {
      throw new IllegalArgumentException("trade counts are invalid");
    }
    requireRatio(pairConcentration, "pair concentration");
    requireRatio(directionalPressure, "directional pressure");
    cooldownUntil = Objects.requireNonNull(cooldownUntil, "cooldown until");
    if ((action == BehaviorRiskAction.PAIR_COOLDOWN) != cooldownUntil.isPresent()) {
      throw new IllegalArgumentException("cooldown instant must match cooldown action");
    }
  }

  private static void requireRatio(BigDecimal value, String name) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be within 0..1");
    }
  }
}
