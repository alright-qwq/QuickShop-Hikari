package com.ghostchu.quickshop.addon.exchange.core.trust;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Applies non-refundable rolling movement budgets to real trades. */
public final class TrustedPriceEngine {
  private static final int EXTRA_CALCULATION_SCALE = 12;

  public Result evaluate(
      TrustedPriceState state, TrustedPricePolicy policy, Trade trade,
      List<TradeInfluence> history, long discoveryQuantity, int priceScale) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(trade, "trade");
    Objects.requireNonNull(history, "history");
    if (discoveryQuantity <= 0 || priceScale < 0) {
      throw new IllegalArgumentException("trusted price quantity and scale must be valid");
    }
    requireChronologicalTrade(state, trade);
    validateHistory(state.marketId(), history, trade);

    TrustedPricePolicy.Tier limits = policy.tier(state.liquidityTier());
    int calculationScale = Math.addExact(priceScale, EXTRA_CALCULATION_SCALE);
    int internalScale = Math.addExact(priceScale, 8);
    long lot = Math.max(1L, Math.ceilDiv(discoveryQuantity, 20L));
    BigDecimal quantityFactor = BigDecimal.valueOf(trade.quantity())
        .divide(BigDecimal.valueOf(lot), calculationScale, RoundingMode.DOWN)
        .min(BigDecimal.ONE);
    int direction = trade.price().compareTo(state.trustedPrice());
    BigDecimal rawDistance = direction == 0 ? BigDecimal.ZERO
        : trade.price().subtract(state.trustedPrice()).abs()
            .divide(state.trustedPrice(), calculationScale, RoundingMode.DOWN);
    BigDecimal requestedMove = rawDistance.min(limits.perTradeCap())
        .multiply(quantityFactor);

    Remaining remaining = remaining(
        history, trade, trade.executedAt(), policy, limits);
    BigDecimal anchorAllowance = anchorAllowance(state, direction, limits, calculationScale);
    BigDecimal acceptedMove = minimum(
        requestedMove, remaining.market(), remaining.buyer(), remaining.seller(),
        remaining.pair(), anchorAllowance).max(BigDecimal.ZERO);
    BigDecimal signedMove = direction < 0 ? acceptedMove.negate() : acceptedMove;
    BigDecimal referenceAfter = state.trustedPrice().multiply(BigDecimal.ONE.add(signedMove))
        .setScale(internalScale, RoundingMode.HALF_UP);
    EnumSet<LimitReason> reasons = limitReasons(
        rawDistance, requestedMove, acceptedMove, remaining, anchorAllowance,
        limits.perTradeCap());

    TrustedPriceState nextState = new TrustedPriceState(
        state.marketId(), referenceAfter, state.guidancePrice(), trade.executedAt(),
        state.liquidityTier(), state.policyVersion(), trade.matchSequence(),
        Math.addExact(state.stateVersion(), 1));
    TradeInfluence influence = new TradeInfluence(
        trade.tradeId(), trade.marketId(), trade.matchSequence(),
        trade.buyerAccountId(), trade.sellerAccountId(),
        TradeInfluence.pairKey(trade.buyerAccountId(), trade.sellerAccountId()),
        trade.price(), trade.quantity(), state.trustedPrice(), referenceAfter,
        requestedMove, acceptedMove, quantityFactor, state.liquidityTier(),
        state.policyVersion(), reasons, trade.executedAt());
    return new Result(nextState, influence);
  }

  private static void requireChronologicalTrade(TrustedPriceState state, Trade trade) {
    if (!state.marketId().equals(trade.marketId())) {
      throw new IllegalArgumentException("trade market does not match trusted price state");
    }
    if (trade.matchSequence() <= state.lastMatchSequence()
        || trade.executedAt().isBefore(state.lastEvaluatedAt())) {
      throw new IllegalArgumentException("trusted price trades must be chronological");
    }
  }

  private static void validateHistory(
      String marketId, List<TradeInfluence> history, Trade trade) {
    long previousSequence = 0;
    Instant previousTime = null;
    for (TradeInfluence influence : history) {
      Objects.requireNonNull(influence, "influence");
      if (!marketId.equals(influence.marketId())) {
        throw new IllegalArgumentException("influence market does not match trusted price state");
      }
      if (influence.matchSequence() <= previousSequence
          || influence.matchSequence() >= trade.matchSequence()
          || (previousTime != null && influence.executedAt().isBefore(previousTime))
          || influence.executedAt().isAfter(trade.executedAt())) {
        throw new IllegalArgumentException("trusted price influence history is not chronological");
      }
      previousSequence = influence.matchSequence();
      previousTime = influence.executedAt();
    }
  }

  private static Remaining remaining(
      List<TradeInfluence> history, Trade trade, Instant now,
      TrustedPricePolicy policy, TrustedPricePolicy.Tier limits) {
    Instant cutoff = now.minus(policy.budgetWindow());
    BigDecimal marketUsed = BigDecimal.ZERO;
    BigDecimal buyerUsed = BigDecimal.ZERO;
    BigDecimal sellerUsed = BigDecimal.ZERO;
    BigDecimal pairUsed = BigDecimal.ZERO;
    String pairKey = TradeInfluence.pairKey(trade.buyerAccountId(), trade.sellerAccountId());
    for (TradeInfluence influence : history) {
      if (influence.executedAt().isBefore(cutoff)) {
        continue;
      }
      BigDecimal move = influence.acceptedMove().abs();
      marketUsed = marketUsed.add(move);
      if (participated(influence, trade.buyerAccountId())) {
        buyerUsed = buyerUsed.add(move);
      }
      if (participated(influence, trade.sellerAccountId())) {
        sellerUsed = sellerUsed.add(move);
      }
      if (pairKey.equals(influence.pairKey())) {
        pairUsed = pairUsed.add(move);
      }
    }
    return new Remaining(
        remaining(limits.marketBudget(), marketUsed),
        remaining(limits.accountBudget(), buyerUsed),
        remaining(limits.accountBudget(), sellerUsed),
        remaining(limits.pairBudget(), pairUsed));
  }

  private static boolean participated(TradeInfluence influence, java.util.UUID accountId) {
    return accountId.equals(influence.buyerAccountId())
        || accountId.equals(influence.sellerAccountId());
  }

  private static BigDecimal remaining(BigDecimal limit, BigDecimal used) {
    return limit.subtract(used).max(BigDecimal.ZERO);
  }

  private static BigDecimal anchorAllowance(
      TrustedPriceState state, int direction,
      TrustedPricePolicy.Tier limits, int calculationScale) {
    if (direction == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal lower = state.guidancePrice()
        .multiply(BigDecimal.ONE.subtract(limits.anchorBand()));
    BigDecimal upper = state.guidancePrice()
        .multiply(BigDecimal.ONE.add(limits.anchorBand()));
    if (direction > 0) {
      if (state.trustedPrice().compareTo(upper) >= 0) {
        return BigDecimal.ZERO;
      }
      return upper.subtract(state.trustedPrice())
          .divide(state.trustedPrice(), calculationScale, RoundingMode.DOWN)
          .max(BigDecimal.ZERO);
    }
    if (state.trustedPrice().compareTo(lower) <= 0) {
      return BigDecimal.ZERO;
    }
    return state.trustedPrice().subtract(lower)
        .divide(state.trustedPrice(), calculationScale, RoundingMode.DOWN)
        .max(BigDecimal.ZERO);
  }

  private static BigDecimal minimum(BigDecimal first, BigDecimal... rest) {
    BigDecimal minimum = first;
    for (BigDecimal value : rest) {
      minimum = minimum.min(value);
    }
    return minimum;
  }

  private static EnumSet<LimitReason> limitReasons(
      BigDecimal rawDistance, BigDecimal requestedMove, BigDecimal acceptedMove,
      Remaining remaining, BigDecimal anchorAllowance, BigDecimal perTradeCap) {
    EnumSet<LimitReason> reasons = EnumSet.noneOf(LimitReason.class);
    if (rawDistance.compareTo(perTradeCap) > 0) {
      reasons.add(LimitReason.LIMITED_BY_TRADE);
    }
    if (requestedMove.signum() == 0 || acceptedMove.compareTo(requestedMove) >= 0) {
      return reasons;
    }
    if (acceptedMove.compareTo(remaining.market()) == 0) {
      reasons.add(LimitReason.LIMITED_BY_MARKET);
    }
    if (acceptedMove.compareTo(remaining.buyer()) == 0
        || acceptedMove.compareTo(remaining.seller()) == 0) {
      reasons.add(LimitReason.LIMITED_BY_ACCOUNT);
    }
    if (acceptedMove.compareTo(remaining.pair()) == 0) {
      reasons.add(LimitReason.LIMITED_BY_PAIR);
    }
    if (acceptedMove.compareTo(anchorAllowance) == 0) {
      reasons.add(LimitReason.LIMITED_BY_ANCHOR);
    }
    return reasons;
  }

  public record Result(TrustedPriceState state, TradeInfluence influence) {
    public Result {
      Objects.requireNonNull(state, "state");
      Objects.requireNonNull(influence, "influence");
    }
  }

  private record Remaining(
      BigDecimal market, BigDecimal buyer, BigDecimal seller, BigDecimal pair) { }
}
