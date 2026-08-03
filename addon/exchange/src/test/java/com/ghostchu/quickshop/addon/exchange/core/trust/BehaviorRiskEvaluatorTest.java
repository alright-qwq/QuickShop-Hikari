package com.ghostchu.quickshop.addon.exchange.core.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BehaviorRiskEvaluatorTest {
  private static final String MARKET = "diamond-usd";
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final UUID FIRST = id(1);
  private static final UUID SECOND = id(2);
  private static final String PAIR = TradeInfluence.pairKey(FIRST, SECOND);
  private final BehaviorRiskEvaluator evaluator =
      new BehaviorRiskEvaluator(BehaviorRiskPolicy.defaults());

  @Test
  void actionEscalationUsesExplicitSeverityInsteadOfDeclarationOrder() {
    assertThat(BehaviorRiskAction.ALERT.isEscalationFrom(BehaviorRiskAction.OBSERVE)).isTrue();
    assertThat(BehaviorRiskAction.PAIR_COOLDOWN.isEscalationFrom(BehaviorRiskAction.ALERT)).isTrue();
    assertThat(BehaviorRiskAction.ALERT.isEscalationFrom(BehaviorRiskAction.ALERT)).isFalse();
    assertThat(BehaviorRiskAction.OBSERVE.isEscalationFrom(BehaviorRiskAction.PAIR_COOLDOWN)).isFalse();
  }

  @Test
  void ordinaryRepeatedPairTradesRemainObservationOnly() {
    List<TradeInfluence> history = IntStream.range(0, 8)
        .mapToObj(index -> influence(
            FIRST, SECOND, NOW.minusSeconds(7L * 60L - index * 60L), index + 1L,
            "100.00", "100.00", "0"))
        .toList();

    BehaviorRiskDecision decision =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.OBSERVE);
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void oneLargePriceDeviationDoesNotTriggerPairCooldown() {
    List<TradeInfluence> history = List.of(influence(
        FIRST, SECOND, NOW.minusSeconds(30), 1, "180.00", "100.00", "0.80"));

    BehaviorRiskDecision decision =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.NORMAL);
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void concentratedTradingWithoutDirectionalPricePressureOnlyRaisesAlert() {
    List<TradeInfluence> history = IntStream.range(0, 24)
        .mapToObj(index -> influence(
            FIRST, SECOND, NOW.minusSeconds(23L * 60L - index * 60L), index + 1L,
            "100.10", "100.00", "0.001"))
        .toList();

    BehaviorRiskDecision decision =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.ALERT);
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void onlySustainedConcentratedDirectionalPressureTriggersPairCooldown() {
    List<TradeInfluence> history = IntStream.range(0, 24)
        .mapToObj(index -> influence(
            FIRST, SECOND, NOW.minusSeconds(23L * 60L - index * 60L), index + 1L,
            "102.00", "100.00", "0.02"))
        .toList();

    BehaviorRiskDecision decision =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.PAIR_COOLDOWN);
    assertThat(decision.cooldownUntil()).contains(NOW.plus(Duration.ofMinutes(5)));
    assertThat(decision.evidence()).contains(
        BehaviorRiskEvidence.REPEATED_PAIR,
        BehaviorRiskEvidence.CONCENTRATED_PAIR,
        BehaviorRiskEvidence.SUSTAINED_ACTIVITY,
        BehaviorRiskEvidence.DIRECTIONAL_PRICE_PRESSURE);
  }

  @Test
  void pairCooldownExpiresAtTheExactCooldownBoundary() {
    List<TradeInfluence> history = IntStream.range(0, 24)
        .mapToObj(index -> influence(
            FIRST, SECOND, NOW.minusSeconds(23L * 60L - index * 60L), index + 1L,
            "102.00", "100.00", "0.02"))
        .toList();

    BehaviorRiskDecision decision = evaluator.evaluate(
        MARKET, PAIR, LiquidityTier.LOW, history, NOW.plus(Duration.ofMinutes(5)));

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.ALERT);
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void pairCooldownExpiresFromTheLastSuspiciousTradeWithoutRenewingItself() {
    List<TradeInfluence> history = IntStream.range(0, 24)
        .mapToObj(index -> influence(
            FIRST, SECOND, NOW.minusSeconds(23L * 60L - index * 60L), index + 1L,
            "102.00", "100.00", "0.02"))
        .toList();

    BehaviorRiskDecision decision = evaluator.evaluate(
        MARKET, PAIR, LiquidityTier.LOW, history, NOW.plus(Duration.ofMinutes(6)));

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.ALERT);
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void oldSuspiciousActivityDecaysOutOfTheWindow() {
    Duration window = BehaviorRiskPolicy.defaults().window();
    List<TradeInfluence> history = IntStream.range(0, 24)
        .mapToObj(index -> influence(
            FIRST, SECOND,
            NOW.minus(window).minusSeconds(24L * 60L - index * 60L), index + 1L,
            "102.00", "100.00", "0.02"))
        .toList();

    BehaviorRiskDecision decision =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);

    assertThat(decision.action()).isEqualTo(BehaviorRiskAction.NORMAL);
    assertThat(decision.pairTrades()).isZero();
    assertThat(decision.cooldownUntil()).isEmpty();
  }

  @Test
  void unrelatedCounterpartiesRemainAvailableDuringPairAssessment() {
    List<TradeInfluence> history = new ArrayList<>();
    for (int index = 0; index < 24; index++) {
      history.add(influence(
          FIRST, SECOND, NOW.minusSeconds(23L * 60L - index * 60L), index + 1L,
          "102.00", "100.00", "0.02"));
    }
    history.add(influence(FIRST, id(3), NOW.minusSeconds(10), 25,
        "100.00", "100.00", "0"));

    BehaviorRiskDecision suspiciousPair =
        evaluator.evaluate(MARKET, PAIR, LiquidityTier.LOW, history, NOW);
    BehaviorRiskDecision unrelatedPair = evaluator.evaluate(
        MARKET, TradeInfluence.pairKey(FIRST, id(3)), LiquidityTier.LOW, history, NOW);

    assertThat(suspiciousPair.action()).isEqualTo(BehaviorRiskAction.PAIR_COOLDOWN);
    assertThat(unrelatedPair.action()).isEqualTo(BehaviorRiskAction.NORMAL);
  }

  private static TradeInfluence influence(
      UUID buyer, UUID seller, Instant executedAt, long sequence,
      String tradePrice, String referenceBefore, String requestedMove) {
    BigDecimal before = new BigDecimal(referenceBefore).setScale(10);
    BigDecimal requested = new BigDecimal(requestedMove);
    return new TradeInfluence(
        new UUID(0, sequence), MARKET, sequence, buyer, seller,
        TradeInfluence.pairKey(buyer, seller), new BigDecimal(tradePrice), 5,
        before, before, requested, BigDecimal.ZERO, BigDecimal.ONE,
        LiquidityTier.LOW, 1, Set.of(), executedAt);
  }

  private static UUID id(int value) {
    return new UUID(0, value);
  }
}
