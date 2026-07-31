package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Reverts a trusted price towards its guidance anchor when no trade has occurred. */
public final class TrustedPriceMaintenance {
  private static final int EXTRA_CALCULATION_SCALE = 12;
  private static final int EXTRA_INTERNAL_SCALE = 8;

  public Result evaluate(
      TrustedPriceState state, TrustedPricePolicy policy, Instant now, int priceScale) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(now, "now");
    if (priceScale < 0) {
      throw new IllegalArgumentException("trusted price scale must be valid");
    }
    if (now.isBefore(state.lastEvaluatedAt())) {
      throw new IllegalArgumentException("trusted price maintenance must be chronological");
    }

    int calculationScale = Math.addExact(priceScale, EXTRA_CALCULATION_SCALE);
    BigDecimal hours = BigDecimal.valueOf(Duration.between(state.lastEvaluatedAt(), now).toMillis())
        .divide(BigDecimal.valueOf(Duration.ofHours(1).toMillis()), calculationScale,
            RoundingMode.DOWN);
    BigDecimal rate = policy.tier(state.liquidityTier()).reversionPerHour();
    int direction = state.guidancePrice().compareTo(state.trustedPrice());
    if (hours.signum() == 0 || rate.signum() == 0 || direction == 0) {
      return new Result(state, null);
    }

    BigDecimal move = state.guidancePrice().subtract(state.trustedPrice()).abs()
        .divide(state.trustedPrice(), calculationScale, RoundingMode.DOWN)
        .min(rate.multiply(hours));
    if (move.signum() == 0) {
      return new Result(state, null);
    }
    BigDecimal signedMove = direction < 0 ? move.negate() : move;
    BigDecimal nextPrice = state.trustedPrice().multiply(BigDecimal.ONE.add(signedMove))
        .setScale(Math.addExact(priceScale, EXTRA_INTERNAL_SCALE), RoundingMode.HALF_UP);
    TrustedPriceState nextState = new TrustedPriceState(
        state.marketId(), nextPrice, state.guidancePrice(), now, state.liquidityTier(),
        state.policyVersion(), state.lastMatchSequence(), Math.addExact(state.stateVersion(), 1));
    TrustedPriceAdjustment adjustment = new TrustedPriceAdjustment(
        adjustmentId(state, now), state.marketId(), AdjustmentType.ANCHOR_REVERSION,
        state.trustedPrice(), nextPrice, state.guidancePrice(), state.guidancePrice(),
        null, "scheduled anchor reversion", state.policyVersion(), now);
    return new Result(nextState, adjustment);
  }

  private static java.util.UUID adjustmentId(TrustedPriceState state, Instant now) {
    String value = state.marketId() + ':' + state.stateVersion() + ':' + now.toEpochMilli()
        + ':' + state.trustedPrice().toPlainString() + ':' + state.guidancePrice().toPlainString();
    return java.util.UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  public record Result(TrustedPriceState state, TrustedPriceAdjustment adjustment) {
    public Result {
      Objects.requireNonNull(state, "state");
    }
  }
}
