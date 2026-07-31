package com.ghostchu.quickshop.addon.exchange.core.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrustedPriceMaintenanceTest {
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final TrustedPricePolicy POLICY = TrustedPricePolicy.defaults();
  private final TrustedPriceMaintenance maintenance = new TrustedPriceMaintenance();

  @Test
  void lowLiquidityReturnsAtHalfPercentPerHourWithoutCreatingTrade() {
    TrustedPriceMaintenance.Result result = maintenance.evaluate(
        state("110.00", "100.00", LiquidityTier.LOW), POLICY,
        NOW.plus(Duration.ofHours(2)), 2);

    assertThat(result.state().trustedPrice()).isEqualByComparingTo("108.9000000000");
    assertThat(result.adjustment().type()).isEqualTo(AdjustmentType.ANCHOR_REVERSION);
    assertThat(result.adjustment().trustedPriceBefore()).isEqualByComparingTo("110.00");
    assertThat(result.adjustment().trustedPriceAfter()).isEqualByComparingTo("108.9000000000");
    assertThat(result.adjustment().actorId()).isNull();
    assertThat(result.state().lastMatchSequence()).isEqualTo(7);
    assertThat(result.state().stateVersion()).isEqualTo(4);
  }

  @Test
  void growingMarketReturnsAtFifteenBasisPointsPerHour() {
    TrustedPriceMaintenance.Result result = maintenance.evaluate(
        state("110.00", "100.00", LiquidityTier.GROWING), POLICY,
        NOW.plus(Duration.ofHours(2)), 2);

    assertThat(result.state().trustedPrice()).isEqualByComparingTo("109.6700000000");
  }

  @Test
  void stableMarketDoesNotGenerateZeroAdjustment() {
    TrustedPriceMaintenance.Result result = maintenance.evaluate(
        state("110.00", "100.00", LiquidityTier.STABLE), POLICY,
        NOW.plus(Duration.ofHours(2)), 2);

    assertThat(result.state()).isEqualTo(state("110.00", "100.00", LiquidityTier.STABLE));
    assertThat(result.adjustment()).isNull();
  }

  @Test
  void longElapsedPeriodStopsExactlyAtGuidancePrice() {
    TrustedPriceMaintenance.Result result = maintenance.evaluate(
        state("100.10", "100.00", LiquidityTier.LOW), POLICY,
        NOW.plus(Duration.ofDays(10)), 2);

    assertThat(result.state().trustedPrice()).isEqualByComparingTo("100.0000000000");
    assertThat(result.adjustment().trustedPriceAfter()).isEqualByComparingTo("100.0000000000");
  }

  @Test
  void equalPriceOrEqualTimeDoesNotCreateAdjustment() {
    assertThat(maintenance.evaluate(
        state("100", "100", LiquidityTier.LOW), POLICY,
        NOW.plus(Duration.ofHours(1)), 2).adjustment()).isNull();
    assertThat(maintenance.evaluate(
        state("110", "100", LiquidityTier.LOW), POLICY, NOW, 2).adjustment()).isNull();
  }

  @Test
  void rejectsTimeTravel() {
    assertThatThrownBy(() -> maintenance.evaluate(
        state("110", "100", LiquidityTier.LOW), POLICY, NOW.minusSeconds(1), 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chronological");
  }

  private static TrustedPriceState state(
      String trusted, String guidance, LiquidityTier tier) {
    return new TrustedPriceState("diamond-usd", new BigDecimal(trusted),
        new BigDecimal(guidance), NOW, tier, 1, 7, 3);
  }
}
