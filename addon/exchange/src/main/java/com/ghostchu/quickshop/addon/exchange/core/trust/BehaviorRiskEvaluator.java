package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Evaluates multiple independent signals before applying a narrow pair-level cooldown. */
public final class BehaviorRiskEvaluator {
  private static final int DIVISION_SCALE = 12;

  private final BehaviorRiskPolicy policy;

  public BehaviorRiskEvaluator(BehaviorRiskPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public BehaviorRiskDecision evaluate(
      String marketId,
      String pairKey,
      LiquidityTier tier,
      List<TradeInfluence> source,
      Instant now) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    if (pairKey == null || pairKey.isBlank()) {
      throw new IllegalArgumentException("pair key is required");
    }
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(now, "now");

    Instant cutoff = now.minus(policy.window());
    List<TradeInfluence> marketEvents = source.stream()
        .map(event -> Objects.requireNonNull(event, "influence"))
        .filter(event -> marketId.equals(event.marketId()))
        .filter(event -> !event.executedAt().isBefore(cutoff)
            && !event.executedAt().isAfter(now))
        .sorted(Comparator.comparing(TradeInfluence::executedAt)
            .thenComparingLong(TradeInfluence::matchSequence))
        .toList();
    List<TradeInfluence> pairEvents = marketEvents.stream()
        .filter(event -> pairKey.equals(event.pairKey()))
        .toList();

    int marketTrades = marketEvents.size();
    int pairTrades = pairEvents.size();
    BigDecimal concentration = marketTrades == 0
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(pairTrades)
            .divide(BigDecimal.valueOf(marketTrades), DIVISION_SCALE, RoundingMode.HALF_UP);
    BigDecimal pressure = directionalPressure(pairEvents);
    Duration span = pairEvents.size() < 2
        ? Duration.ZERO
        : Duration.between(
            pairEvents.getFirst().executedAt(), pairEvents.getLast().executedAt());

    int observeTrades = adjusted(policy.observePairTrades(), tier);
    int alertTrades = adjusted(policy.alertPairTrades(), tier);
    int cooldownTrades = adjusted(policy.cooldownPairTrades(), tier);
    BigDecimal alertConcentration = adjusted(policy.alertPairConcentration(), tier);
    BigDecimal cooldownConcentration = adjusted(policy.cooldownPairConcentration(), tier);
    BigDecimal pressureThreshold = adjusted(policy.directionalPressure(), tier);
    Duration sustainedSpan = adjusted(policy.sustainedSpan(), tier);

    EnumSet<BehaviorRiskEvidence> evidence = EnumSet.noneOf(BehaviorRiskEvidence.class);
    if (pairTrades >= observeTrades) {
      evidence.add(BehaviorRiskEvidence.REPEATED_PAIR);
    }
    if (pairTrades >= alertTrades && concentration.compareTo(alertConcentration) >= 0) {
      evidence.add(BehaviorRiskEvidence.CONCENTRATED_PAIR);
    }
    if (pairTrades >= alertTrades && span.compareTo(sustainedSpan) >= 0) {
      evidence.add(BehaviorRiskEvidence.SUSTAINED_ACTIVITY);
    }
    if (pairTrades >= alertTrades && pressure.compareTo(pressureThreshold) >= 0) {
      evidence.add(BehaviorRiskEvidence.DIRECTIONAL_PRICE_PRESSURE);
    }

    Instant lastPairTrade = pairEvents.isEmpty()
        ? Instant.EPOCH : pairEvents.getLast().executedAt();
    boolean cooldown = pairTrades >= cooldownTrades
        && lastPairTrade.plus(policy.cooldown()).isAfter(now)
        && concentration.compareTo(cooldownConcentration) >= 0
        && evidence.containsAll(Set.of(
            BehaviorRiskEvidence.REPEATED_PAIR,
            BehaviorRiskEvidence.CONCENTRATED_PAIR,
            BehaviorRiskEvidence.SUSTAINED_ACTIVITY,
            BehaviorRiskEvidence.DIRECTIONAL_PRICE_PRESSURE));
    BehaviorRiskAction action;
    Optional<Instant> cooldownUntil;
    if (cooldown) {
      action = BehaviorRiskAction.PAIR_COOLDOWN;
      cooldownUntil = Optional.of(lastPairTrade.plus(policy.cooldown()));
    } else if (evidence.size() >= 2) {
      action = BehaviorRiskAction.ALERT;
      cooldownUntil = Optional.empty();
    } else if (evidence.contains(BehaviorRiskEvidence.REPEATED_PAIR)) {
      action = BehaviorRiskAction.OBSERVE;
      cooldownUntil = Optional.empty();
    } else {
      action = BehaviorRiskAction.NORMAL;
      cooldownUntil = Optional.empty();
    }

    return new BehaviorRiskDecision(
        marketId, pairKey, action, evidence, pairTrades, marketTrades,
        concentration, pressure, cooldownUntil);
  }

  private static BigDecimal directionalPressure(List<TradeInfluence> events) {
    BigDecimal upward = BigDecimal.ZERO;
    BigDecimal downward = BigDecimal.ZERO;
    for (TradeInfluence event : events) {
      int direction = event.tradePrice().compareTo(event.referenceBefore());
      if (direction > 0) {
        upward = upward.add(event.requestedMove());
      } else if (direction < 0) {
        downward = downward.add(event.requestedMove());
      }
    }
    return upward.subtract(downward).abs().min(BigDecimal.ONE);
  }

  private static int adjusted(int threshold, LiquidityTier tier) {
    return switch (tier) {
      case LOW -> threshold;
      case GROWING -> multiplyCeiling(threshold, new BigDecimal("1.25"));
      case STABLE -> multiplyCeiling(threshold, new BigDecimal("1.50"));
    };
  }

  private static BigDecimal adjusted(BigDecimal threshold, LiquidityTier tier) {
    BigDecimal multiplier = switch (tier) {
      case LOW -> BigDecimal.ONE;
      case GROWING -> new BigDecimal("1.10");
      case STABLE -> new BigDecimal("1.20");
    };
    return threshold.multiply(multiplier).min(BigDecimal.ONE);
  }

  private static Duration adjusted(Duration threshold, LiquidityTier tier) {
    long numerator = switch (tier) {
      case LOW -> 100L;
      case GROWING -> 125L;
      case STABLE -> 150L;
    };
    return threshold.multipliedBy(numerator).dividedBy(100L);
  }

  private static int multiplyCeiling(int value, BigDecimal multiplier) {
    return BigDecimal.valueOf(value).multiply(multiplier)
        .setScale(0, RoundingMode.CEILING).intValueExact();
  }
}
