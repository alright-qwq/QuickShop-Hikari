package com.ghostchu.quickshop.addon.exchange.core.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TrustedPricePolicyTest {
  @Test
  void defaultsMatchApprovedLowLiquidityLimits() {
    TrustedPricePolicy policy = TrustedPricePolicy.defaults();

    TrustedPricePolicy.Tier low = policy.tier(LiquidityTier.LOW);

    assertThat(policy.budgetWindow()).isEqualTo(Duration.ofHours(6));
    assertThat(policy.confidenceWindow()).isEqualTo(Duration.ofHours(24));
    assertThat(low.perTradeCap()).isEqualByComparingTo("0.005");
    assertThat(low.marketBudget()).isEqualByComparingTo("0.030");
    assertThat(low.accountBudget()).isEqualByComparingTo("0.015");
    assertThat(low.pairBudget()).isEqualByComparingTo("0.0075");
    assertThat(low.anchorBand()).isEqualByComparingTo("0.10");
    assertThat(low.reversionPerHour()).isEqualByComparingTo("0.005");
  }

  @Test
  void defaultsContainApprovedGrowingAndStableLimits() {
    TrustedPricePolicy policy = TrustedPricePolicy.defaults();

    assertThat(policy.tier(LiquidityTier.GROWING))
        .isEqualTo(new TrustedPricePolicy.Tier(
            bd("0.015"), bd("0.080"), bd("0.040"), bd("0.020"),
            bd("0.25"), bd("0.0015")));
    assertThat(policy.tier(LiquidityTier.STABLE))
        .isEqualTo(new TrustedPricePolicy.Tier(
            bd("0.030"), bd("0.200"), bd("0.080"), bd("0.040"),
            bd("0.60"), BigDecimal.ZERO));
  }

  @Test
  void rejectsBudgetOrderThatWouldBypassPairProtection() {
    assertThatThrownBy(() -> new TrustedPricePolicy.Tier(
        bd("0.01"), bd("0.02"), bd("0.01"), bd("0.011"),
        bd("0.10"), BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("budgets");
  }

  @Test
  void rejectsMissingTierAndInvalidWindow() {
    assertThatThrownBy(() -> new TrustedPricePolicy(
        Duration.ZERO, Duration.ofHours(24), TrustedPricePolicy.defaults().tiers()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
    assertThatThrownBy(() -> new TrustedPricePolicy(
        Duration.ofHours(6), Duration.ofHours(24),
        java.util.Map.of(LiquidityTier.LOW,
            TrustedPricePolicy.defaults().tier(LiquidityTier.LOW))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tier");
  }

  @Test
  void stateKeepsInternalPrecisionAndCanChangeOnlyLiquidityTier() {
    TrustedPriceState state = new TrustedPriceState(
        "diamond-usd", bd("100.1234567890"), bd("100.00"), Instant.EPOCH,
        LiquidityTier.LOW, 1, 7, 3);

    TrustedPriceState changed = state.withLiquidityTier(LiquidityTier.GROWING);

    assertThat(changed.trustedPrice()).isEqualByComparingTo("100.1234567890");
    assertThat(changed.guidancePrice()).isEqualByComparingTo("100.00");
    assertThat(changed.liquidityTier()).isEqualTo(LiquidityTier.GROWING);
    assertThat(changed.lastMatchSequence()).isEqualTo(7);
    assertThat(changed.stateVersion()).isEqualTo(3);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
