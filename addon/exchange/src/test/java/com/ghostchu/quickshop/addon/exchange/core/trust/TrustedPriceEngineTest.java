package com.ghostchu.quickshop.addon.exchange.core.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustedPriceEngineTest {
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final UUID A = id(1);
  private static final UUID B = id(2);
  private static final TrustedPricePolicy POLICY = TrustedPricePolicy.defaults();
  private static final long DISCOVERY_QUANTITY = 100;
  private static final int PRICE_SCALE = 2;
  private final TrustedPriceEngine engine = new TrustedPriceEngine();

  @Test
  void pairBudgetStopsAlternatingTwoPlayerManipulation() {
    TrustedPriceState start = state("100.0000000000", "100.00", LiquidityTier.LOW, NOW, 0, 0);

    TrustedPriceEngine.Result first = engine.evaluate(start, POLICY,
        trade(A, B, "115.00", 5, 1, NOW.plusSeconds(1)), List.of(),
        DISCOVERY_QUANTITY, PRICE_SCALE);
    assertThat(first.state().trustedPrice()).isEqualByComparingTo("100.5000000000");
    assertThat(first.influence().acceptedMove()).isEqualByComparingTo("0.005");
    assertThat(first.influence().reasons()).contains(LimitReason.LIMITED_BY_TRADE);

    TrustedPriceEngine.Result second = engine.evaluate(first.state(), POLICY,
        trade(B, A, "85.00", 5, 2, NOW.plusSeconds(2)), List.of(first.influence()),
        DISCOVERY_QUANTITY, PRICE_SCALE);
    assertThat(second.state().trustedPrice()).isEqualByComparingTo("100.2487500000");
    assertThat(second.influence().acceptedMove()).isEqualByComparingTo("0.0025");
    assertThat(second.influence().reasons()).contains(LimitReason.LIMITED_BY_PAIR);

    TrustedPriceEngine.Result third = engine.evaluate(second.state(), POLICY,
        trade(A, B, "115.00", 5, 3, NOW.plusSeconds(3)),
        List.of(first.influence(), second.influence()), DISCOVERY_QUANTITY, PRICE_SCALE);
    assertThat(third.state().trustedPrice()).isEqualByComparingTo("100.2487500000");
    assertThat(third.influence().acceptedMove()).isZero();
    assertThat(third.influence().reasons()).contains(LimitReason.LIMITED_BY_PAIR);
  }

  @Test
  void reverseTradeConsumesRatherThanRefundsPairBudget() {
    TrustedPriceEngine.Result first = evaluate(state(), trade(A, B, "115", 5, 1, NOW.plusSeconds(1)),
        List.of());
    TrustedPriceEngine.Result reverse = evaluate(first.state(),
        trade(B, A, "85", 5, 2, NOW.plusSeconds(2)), List.of(first.influence()));
    TrustedPriceEngine.Result forwardAgain = evaluate(reverse.state(),
        trade(A, B, "115", 5, 3, NOW.plusSeconds(3)),
        List.of(first.influence(), reverse.influence()));

    assertThat(first.influence().acceptedMove().add(reverse.influence().acceptedMove()))
        .isEqualByComparingTo("0.0075");
    assertThat(forwardAgain.influence().acceptedMove()).isZero();
  }

  @Test
  void expiredPairEventsReleaseBudget() {
    TrustedPriceEngine.Result first = evaluate(state(),
        trade(A, B, "115", 5, 1, NOW.plusSeconds(1)), List.of());
    Instant afterWindow = NOW.plus(POLICY.budgetWindow()).plusSeconds(2);

    TrustedPriceEngine.Result afterExpiry = evaluate(
        new TrustedPriceState("diamond-usd", first.state().trustedPrice(), bd("100"),
            first.state().lastEvaluatedAt(), LiquidityTier.LOW, 1, 1, 1),
        trade(A, B, "115", 5, 2, afterWindow), List.of(first.influence()));

    assertThat(afterExpiry.influence().acceptedMove()).isEqualByComparingTo("0.005");
  }

  @Test
  void quantityBelowDiscoveryLotReceivesProportionalInfluence() {
    TrustedPriceEngine.Result result = evaluate(state(),
        trade(A, B, "115", 1, 1, NOW.plusSeconds(1)), List.of());

    assertThat(result.influence().quantityFactor()).isEqualByComparingTo("0.2");
    assertThat(result.influence().requestedMove()).isEqualByComparingTo("0.0010");
    assertThat(result.state().trustedPrice()).isEqualByComparingTo("100.1000000000");
  }

  @Test
  void accountBudgetAppliesAcrossDifferentCounterparties() {
    TrustedPriceState current = state();
    List<TradeInfluence> history = new ArrayList<>();
    for (int sequence = 1; sequence <= 3; sequence++) {
      TrustedPriceEngine.Result result = evaluate(current,
          trade(A, id(sequence + 1), "115", 5, sequence, NOW.plusSeconds(sequence)), history);
      assertThat(result.influence().acceptedMove()).isEqualByComparingTo("0.005");
      current = result.state();
      history.add(result.influence());
    }

    TrustedPriceEngine.Result blocked = evaluate(current,
        trade(A, id(5), "115", 5, 4, NOW.plusSeconds(4)), history);

    assertThat(blocked.influence().acceptedMove()).isZero();
    assertThat(blocked.influence().reasons()).contains(LimitReason.LIMITED_BY_ACCOUNT);
  }

  @Test
  void marketBudgetAppliesAcrossUnrelatedPairs() {
    TrustedPriceState current = state();
    List<TradeInfluence> history = new ArrayList<>();
    for (int sequence = 1; sequence <= 6; sequence++) {
      TrustedPriceEngine.Result result = evaluate(current,
          trade(id(sequence * 2), id(sequence * 2 + 1), "115", 5, sequence,
              NOW.plusSeconds(sequence)), history);
      assertThat(result.influence().acceptedMove()).isEqualByComparingTo("0.005");
      current = result.state();
      history.add(result.influence());
    }

    TrustedPriceEngine.Result blocked = evaluate(current,
        trade(id(20), id(21), "115", 5, 7, NOW.plusSeconds(7)), history);

    assertThat(blocked.influence().acceptedMove()).isZero();
    assertThat(blocked.influence().reasons()).contains(LimitReason.LIMITED_BY_MARKET);
  }

  @Test
  void guidanceBandStopsOutwardMovementButAllowsInwardMovement() {
    TrustedPriceState nearUpper = state(
        "109.8000000000", "100.00", LiquidityTier.LOW, NOW, 0, 0);

    TrustedPriceEngine.Result toBoundary = evaluate(nearUpper,
        trade(A, B, "120", 5, 1, NOW.plusSeconds(1)), List.of());
    assertThat(toBoundary.state().trustedPrice()).isEqualByComparingTo("110.0000000000");
    assertThat(toBoundary.influence().reasons()).contains(LimitReason.LIMITED_BY_ANCHOR);

    TrustedPriceEngine.Result outwardBlocked = evaluate(toBoundary.state(),
        trade(A, B, "120", 5, 2, NOW.plusSeconds(2)), List.of());
    assertThat(outwardBlocked.influence().acceptedMove()).isZero();
    assertThat(outwardBlocked.influence().reasons()).contains(LimitReason.LIMITED_BY_ANCHOR);

    TrustedPriceEngine.Result inward = evaluate(toBoundary.state(),
        trade(A, B, "90", 5, 2, NOW.plusSeconds(2)), List.of());
    assertThat(inward.influence().acceptedMove()).isEqualByComparingTo("0.005");
    assertThat(inward.state().trustedPrice()).isEqualByComparingTo("109.4500000000");
  }

  @Test
  void rejectsNonChronologicalOrWrongMarketTrade() {
    assertThatThrownBy(() -> evaluate(state(),
        trade(A, B, "100", 5, 1, NOW.minusSeconds(1)), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chronological");
    Trade other = trade(A, B, "100", 5, 1, NOW.plusSeconds(1), "other-market");
    assertThatThrownBy(() -> evaluate(state(), other, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("market");
  }

  private TrustedPriceEngine.Result evaluate(
      TrustedPriceState state, Trade trade, List<TradeInfluence> history) {
    return engine.evaluate(
        state, POLICY, trade, history, DISCOVERY_QUANTITY, PRICE_SCALE);
  }

  private static TrustedPriceState state() {
    return state("100.0000000000", "100.00", LiquidityTier.LOW, NOW, 0, 0);
  }

  private static TrustedPriceState state(
      String trusted, String guidance, LiquidityTier tier, Instant evaluatedAt,
      long matchSequence, long stateVersion) {
    return new TrustedPriceState("diamond-usd", bd(trusted), bd(guidance), evaluatedAt,
        tier, 1, matchSequence, stateVersion);
  }

  private static Trade trade(
      UUID buyer, UUID seller, String price, long quantity, long sequence, Instant at) {
    return trade(buyer, seller, price, quantity, sequence, at, "diamond-usd");
  }

  private static Trade trade(
      UUID buyer, UUID seller, String price, long quantity, long sequence,
      Instant at, String marketId) {
    return new Trade(new UUID(10, sequence), marketId,
        new UUID(20, sequence), new UUID(30, sequence), buyer, seller,
        bd(price), quantity, BigDecimal.ZERO, BigDecimal.ZERO, sequence, at);
  }

  private static UUID id(int value) {
    return new UUID(0, value);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
