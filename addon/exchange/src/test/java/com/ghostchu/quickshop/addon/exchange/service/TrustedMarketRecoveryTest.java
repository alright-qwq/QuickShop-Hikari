package com.ghostchu.quickshop.addon.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.trust.LimitReason;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceEngine;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustedMarketRecoveryTest {
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void legacyReplayPersistsPairBudgetExactlyOnceAcrossRestarts() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    fixture.repository().inTransaction(tx -> {
      MarketState state = tx.marketState("diamond-usd");
      tx.insertTrade(trade(A, B, "115.00", 1, NOW));
      tx.insertTrade(trade(B, A, "85.00", 2, NOW.plusSeconds(1)));
      tx.updateMarketState(new MarketState(
          state.marketId(), state.status(), state.prioritySequence(), 2,
          new BigDecimal("100.00"), new BigDecimal("85.00"), state.haltedUntil(),
          100L, state.circuitBreakerLevel(), state.version() + 1), state.version());
      return null;
    });

    RecoveredMarket first = fixture.recovery().recover("diamond-usd", NOW.plusSeconds(2));
    RecoveredMarket second = fixture.recovery().recover("diamond-usd", NOW.plusSeconds(3));

    assertThat(first.trustedPriceState().trustedPrice())
        .isEqualByComparingTo("100.2487500000");
    assertThat(first.recentInfluences()).hasSize(2);
    assertThat(second.trustedPriceState()).isEqualTo(first.trustedPriceState());
    assertThat(second.recentInfluences()).containsExactlyElementsOf(first.recentInfluences());

    TrustedPriceEngine.Result next = new TrustedPriceEngine().evaluate(
        second.trustedPriceState(), TrustedPricePolicy.defaults(),
        trade(A, B, "115.00", 3, NOW.plusSeconds(4)), second.recentInfluences(), 100, 2);
    assertThat(next.influence().acceptedMove()).isZero();
    assertThat(next.influence().reasons()).contains(LimitReason.LIMITED_BY_PAIR);
  }

  @Test
  void trustedReplayUsesConfiguredDiscoveryQuantity() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    Trade persisted = trade(A, B, "115.00", 1, NOW);
    fixture.repository().inTransaction(tx -> {
      MarketState state = tx.marketState("diamond-usd");
      tx.insertTrade(persisted);
      tx.updateMarketState(new MarketState(
          state.marketId(), state.status(), state.prioritySequence(), 1,
          new BigDecimal("100.00"), persisted.price(), state.haltedUntil(),
          5L, state.circuitBreakerLevel(), state.version() + 1), state.version());
      return null;
    });
    TrustedPriceState seed = new TrustedPriceState(
        "diamond-usd", new BigDecimal("100.00"), new BigDecimal("100.00"),
        NOW, LiquidityTier.LOW, 1, 0, 0);
    TrustedPriceState expected = new TrustedPriceEngine().evaluate(
        seed, TrustedPricePolicy.defaults(), persisted, List.of(), 200L, 2).state();

    RecoveredMarket recovered = new OrderBookRecoveryService(
        fixture.repository(), fixture.rules(), RiskLimits.defaults(), 200L)
        .recover("diamond-usd", NOW.plusSeconds(1));

    assertThat(recovered.trustedPriceState()).isEqualTo(expected);
  }

  private static Trade trade(
      UUID buyer, UUID seller, String price, long sequence, Instant executedAt) {
    return new Trade(UUID.nameUUIDFromBytes(("trade-" + sequence).getBytes()), "diamond-usd",
        UUID.nameUUIDFromBytes(("maker-" + sequence).getBytes()),
        UUID.nameUUIDFromBytes(("taker-" + sequence).getBytes()), buyer, seller,
        new BigDecimal(price), 5, BigDecimal.ZERO, BigDecimal.ZERO, sequence, executedAt);
  }
}
