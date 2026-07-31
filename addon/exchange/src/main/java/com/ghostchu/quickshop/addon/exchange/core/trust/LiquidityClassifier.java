package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Classifies sparse Minecraft markets without allowing repeated pair volume to imply diversity. */
public final class LiquidityClassifier {
  private static final int PAIR_TRADE_CAP = 5;
  private static final int ACCOUNT_TRADE_CAP = 10;
  private static final long FOUR_HOURS_SECONDS = 4L * 60L * 60L;
  private static final int DIVISION_SCALE = 12;

  private final TrustedPricePolicy policy;

  public LiquidityClassifier(TrustedPricePolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public LiquiditySnapshot classify(List<TradeInfluence> source, Instant now, long lot) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(now, "now");
    if (lot <= 0) {
      throw new IllegalArgumentException("liquidity lot must be positive");
    }
    Instant cutoff = now.minus(policy.confidenceWindow());
    List<TradeInfluence> events = source.stream()
        .map(event -> Objects.requireNonNull(event, "influence"))
        .filter(event -> !event.executedAt().isBefore(cutoff)
            && !event.executedAt().isAfter(now))
        .sorted(Comparator.comparing(TradeInfluence::executedAt)
            .thenComparingLong(TradeInfluence::matchSequence))
        .toList();

    Set<UUID> participants = new HashSet<>();
    Set<String> pairs = new HashSet<>();
    Set<Long> buckets = new HashSet<>();
    Map<UUID, Integer> effectiveByAccount = new HashMap<>();
    Map<String, Integer> effectiveByPair = new HashMap<>();
    Map<UUID, BigDecimal> accountWeights = new HashMap<>();
    Map<String, BigDecimal> pairWeights = new HashMap<>();
    int effectiveTrades = 0;
    long weightCap;
    try {
      weightCap = Math.multiplyExact(lot, 5L);
    } catch (ArithmeticException overflow) {
      weightCap = Long.MAX_VALUE;
    }

    for (TradeInfluence event : events) {
      participants.add(event.buyerAccountId());
      participants.add(event.sellerAccountId());
      pairs.add(event.pairKey());
      buckets.add(Math.floorDiv(event.executedAt().getEpochSecond(), FOUR_HOURS_SECONDS));

      int pairCount = effectiveByPair.getOrDefault(event.pairKey(), 0);
      int buyerCount = effectiveByAccount.getOrDefault(event.buyerAccountId(), 0);
      int sellerCount = effectiveByAccount.getOrDefault(event.sellerAccountId(), 0);
      if (pairCount < PAIR_TRADE_CAP
          && buyerCount < ACCOUNT_TRADE_CAP && sellerCount < ACCOUNT_TRADE_CAP) {
        effectiveTrades++;
        effectiveByPair.put(event.pairKey(), pairCount + 1);
        effectiveByAccount.put(event.buyerAccountId(), buyerCount + 1);
        effectiveByAccount.put(event.sellerAccountId(), sellerCount + 1);
      }

      BigDecimal weight = BigDecimal.valueOf(Math.min(event.quantity(), weightCap));
      accountWeights.merge(event.buyerAccountId(), weight, BigDecimal::add);
      accountWeights.merge(event.sellerAccountId(), weight, BigDecimal::add);
      pairWeights.merge(event.pairKey(), weight, BigDecimal::add);
    }

    BigDecimal accountConcentration = concentration(accountWeights.values());
    BigDecimal pairConcentration = concentration(pairWeights.values());
    LiquidityTier tier = classifyTier(participants.size(), pairs.size(), effectiveTrades,
        buckets.size(), accountConcentration, pairConcentration);
    return new LiquiditySnapshot(tier, participants, pairs, effectiveTrades, buckets.size(),
        accountConcentration, pairConcentration);
  }

  private static LiquidityTier classifyTier(
      int participants, int pairs, int effectiveTrades, int activeBuckets,
      BigDecimal accountConcentration, BigDecimal pairConcentration) {
    if (participants >= 8 && pairs >= 6 && effectiveTrades >= 20 && activeBuckets >= 4
        && accountConcentration.compareTo(new BigDecimal("0.35")) <= 0
        && pairConcentration.compareTo(new BigDecimal("0.25")) <= 0) {
      return LiquidityTier.STABLE;
    }
    if (participants >= 4 && pairs >= 3 && effectiveTrades >= 6 && activeBuckets >= 2
        && accountConcentration.compareTo(new BigDecimal("0.60")) <= 0
        && pairConcentration.compareTo(new BigDecimal("0.50")) <= 0) {
      return LiquidityTier.GROWING;
    }
    return LiquidityTier.LOW;
  }

  private static BigDecimal concentration(java.util.Collection<BigDecimal> weights) {
    if (weights.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal maximum = BigDecimal.ZERO;
    for (BigDecimal weight : weights) {
      total = total.add(weight);
      maximum = maximum.max(weight);
    }
    if (total.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return maximum.divide(total, DIVISION_SCALE, RoundingMode.HALF_UP);
  }
}
