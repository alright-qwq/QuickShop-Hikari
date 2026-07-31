package com.ghostchu.quickshop.addon.exchange.core.trust;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable record of how much one real trade was allowed to move the trusted price. */
public record TradeInfluence(
    UUID tradeId, String marketId, long matchSequence,
    UUID buyerAccountId, UUID sellerAccountId, String pairKey,
    BigDecimal tradePrice, long quantity, BigDecimal referenceBefore,
    BigDecimal referenceAfter, BigDecimal requestedMove, BigDecimal acceptedMove,
    BigDecimal quantityFactor, LiquidityTier tier, long policyVersion,
    Set<LimitReason> reasons, Instant executedAt) {

  public TradeInfluence {
    Objects.requireNonNull(tradeId, "tradeId");
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    Objects.requireNonNull(buyerAccountId, "buyerAccountId");
    Objects.requireNonNull(sellerAccountId, "sellerAccountId");
    if (buyerAccountId.equals(sellerAccountId)) {
      throw new IllegalArgumentException("trade parties must be distinct");
    }
    String expectedPair = pairKey(buyerAccountId, sellerAccountId);
    if (!expectedPair.equals(pairKey)) {
      throw new IllegalArgumentException("trade pair key is not canonical");
    }
    requirePositive(tradePrice, "trade price");
    requirePositive(referenceBefore, "reference before");
    requirePositive(referenceAfter, "reference after");
    requireRatio(requestedMove, "requested move");
    requireRatio(acceptedMove, "accepted move");
    requireRatio(quantityFactor, "quantity factor");
    if (matchSequence <= 0 || quantity <= 0 || policyVersion <= 0
        || acceptedMove.compareTo(requestedMove) > 0) {
      throw new IllegalArgumentException("trade influence values are invalid");
    }
    Objects.requireNonNull(tier, "tier");
    reasons = Set.copyOf(Objects.requireNonNull(reasons, "reasons"));
    Objects.requireNonNull(executedAt, "executedAt");
  }

  public static String pairKey(UUID left, UUID right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    if (left.equals(right)) {
      throw new IllegalArgumentException("trade parties must be distinct");
    }
    String first = left.toString();
    String second = right.toString();
    return first.compareTo(second) <= 0
        ? first + ':' + second
        : second + ':' + first;
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireRatio(BigDecimal value, String name) {
    if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(name + " must be within 0..1");
    }
  }
}
