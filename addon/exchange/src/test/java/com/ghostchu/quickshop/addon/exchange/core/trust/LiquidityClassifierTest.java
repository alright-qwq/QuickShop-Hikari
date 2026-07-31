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

class LiquidityClassifierTest {
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final long LOT = 5;
  private final LiquidityClassifier classifier =
      new LiquidityClassifier(TrustedPricePolicy.defaults());

  @Test
  void twoAccountsRemainLowRegardlessOfTradeCount() {
    UUID first = id(1);
    UUID second = id(2);
    List<TradeInfluence> events = IntStream.range(0, 100)
        .mapToObj(index -> influence(
            first, second, NOW.minusSeconds(index * 60L), index + 1L, 64))
        .toList();

    LiquiditySnapshot result = classifier.classify(events, NOW, LOT);

    assertThat(result.tier()).isEqualTo(LiquidityTier.LOW);
    assertThat(result.participants()).isEqualTo(2);
    assertThat(result.pairs()).isEqualTo(1);
    assertThat(result.effectiveTrades()).isEqualTo(5);
  }

  @Test
  void diverseTimeDistributedMarketBecomesStable() {
    List<TradeInfluence> events = new ArrayList<>();
    UUID[] accounts = IntStream.rangeClosed(1, 8).mapToObj(LiquidityClassifierTest::id)
        .toArray(UUID[]::new);
    long sequence = 1;
    for (int bucket = 0; bucket < 4; bucket++) {
      for (int offset = 0; offset < 5; offset++) {
        int pair = (bucket * 5 + offset) % 8;
        events.add(influence(accounts[pair], accounts[(pair + 1) % 8],
            NOW.minus(Duration.ofHours(18L - bucket * 4L)).plusSeconds(offset),
            sequence++, 5));
      }
    }

    LiquiditySnapshot result = classifier.classify(events, NOW, LOT);

    assertThat(result.tier()).isEqualTo(LiquidityTier.STABLE);
    assertThat(result.participants()).isEqualTo(8);
    assertThat(result.pairs()).isEqualTo(8);
    assertThat(result.effectiveTrades()).isEqualTo(20);
    assertThat(result.activeBuckets()).isEqualTo(4);
    assertThat(result.accountConcentration()).isLessThanOrEqualTo(new BigDecimal("0.35"));
    assertThat(result.pairConcentration()).isLessThanOrEqualTo(new BigDecimal("0.25"));
  }

  @Test
  void fourAccountsAndThreePairsBecomeGrowing() {
    UUID[] accounts = IntStream.rangeClosed(1, 4).mapToObj(LiquidityClassifierTest::id)
        .toArray(UUID[]::new);
    List<TradeInfluence> events = List.of(
        influence(accounts[0], accounts[1], NOW.minus(Duration.ofHours(5)), 1, 5),
        influence(accounts[0], accounts[1], NOW.minus(Duration.ofHours(5)).plusSeconds(1), 2, 5),
        influence(accounts[1], accounts[2], NOW.minus(Duration.ofHours(4)), 3, 5),
        influence(accounts[1], accounts[2], NOW.minus(Duration.ofHours(4)).plusSeconds(1), 4, 5),
        influence(accounts[2], accounts[3], NOW.minus(Duration.ofMinutes(10)), 5, 5),
        influence(accounts[2], accounts[3], NOW.minus(Duration.ofMinutes(9)), 6, 5));

    LiquiditySnapshot result = classifier.classify(events, NOW, LOT);

    assertThat(result.tier()).isEqualTo(LiquidityTier.GROWING);
    assertThat(result.activeBuckets()).isEqualTo(2);
  }

  @Test
  void concentratedPairCannotUpgradeMarketByAddingTokenCounterparties() {
    UUID first = id(1);
    UUID second = id(2);
    List<TradeInfluence> events = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      events.add(influence(first, second, NOW.minusSeconds(3600L + index), index + 1L, 5));
    }
    events.add(influence(first, id(3), NOW.minusSeconds(120), 41, 1));
    events.add(influence(second, id(4), NOW.minusSeconds(60), 42, 1));

    LiquiditySnapshot result = classifier.classify(events, NOW, LOT);

    assertThat(result.participants()).isEqualTo(4);
    assertThat(result.pairs()).isEqualTo(3);
    assertThat(result.tier()).isEqualTo(LiquidityTier.LOW);
    assertThat(result.pairConcentration()).isGreaterThan(new BigDecimal("0.90"));
  }

  @Test
  void ignoresEventsOlderThanConfidenceWindowButIncludesExactCutoff() {
    Duration window = TrustedPricePolicy.defaults().confidenceWindow();
    List<TradeInfluence> events = List.of(
        influence(id(1), id(2), NOW.minus(window).minusMillis(1), 1, 5),
        influence(id(3), id(4), NOW.minus(window), 2, 5));

    LiquiditySnapshot result = classifier.classify(events, NOW, LOT);

    assertThat(result.participantIds()).containsExactlyInAnyOrder(id(3), id(4));
    assertThat(result.pairs()).isEqualTo(1);
  }

  private static TradeInfluence influence(
      UUID buyer, UUID seller, Instant executedAt, long sequence, long quantity) {
    BigDecimal price = new BigDecimal("100.0000000000");
    return new TradeInfluence(
        new UUID(0, sequence), "diamond-usd", sequence, buyer, seller,
        TradeInfluence.pairKey(buyer, seller), new BigDecimal("100.00"), quantity,
        price, price, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
        LiquidityTier.LOW, 1, Set.of(), executedAt);
  }

  private static UUID id(int value) {
    return new UUID(0, value);
  }
}
