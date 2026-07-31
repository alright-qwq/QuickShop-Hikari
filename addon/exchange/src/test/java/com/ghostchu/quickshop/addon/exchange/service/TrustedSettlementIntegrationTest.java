package com.ghostchu.quickshop.addon.exchange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.trust.LimitReason;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.repository.TrustedMarketSnapshot;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustedSettlementIntegrationTest {
  @Test
  void configuredTrustedPolicyControlsSettlementInfluence() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    EnumMap<LiquidityTier, TrustedPricePolicy.Tier> tiers =
        new EnumMap<>(LiquidityTier.class);
    tiers.putAll(TrustedPricePolicy.defaults().tiers());
    TrustedPricePolicy.Tier defaults = tiers.get(LiquidityTier.LOW);
    tiers.put(LiquidityTier.LOW, new TrustedPricePolicy.Tier(
        new BigDecimal("0.001"), defaults.marketBudget(), defaults.accountBudget(),
        defaults.pairBudget(), defaults.anchorBand(), defaults.reversionPerHour()));
    TrustedPricePolicy policy = new TrustedPricePolicy(
        TrustedPricePolicy.defaults().budgetWindow(),
        TrustedPricePolicy.defaults().confidenceWindow(), tiers);
    PersistentOrderService service =
        fixture.serviceWithMarketDataAndTrustedPolicy(marketData, policy);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    UUID seller = fixture.accountWithItems(5);

    trade(service, seller, buyer, "115.00");

    TrustedMarketSnapshot trusted = fixture.repository().inTransaction(tx ->
        tx.trustedMarketSnapshot("diamond-usd", Instant.EPOCH, Instant.EPOCH));
    assertThat(trusted.influences()).singleElement()
        .satisfies(influence -> assertThat(influence.acceptedMove())
            .isEqualByComparingTo("0.001"));
    assertThat(trusted.state().trustedPrice()).isEqualByComparingTo("100.1000000000");
  }

  @Test
  void rawTradesSettleWhilePairBudgetBoundsTheTrustedQuote() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    MarketDataService marketData = new MarketDataService(new CandleAggregator());
    PersistentOrderService service = fixture.serviceWithMarketData(marketData);
    UUID accountA = fixture.accountWithCurrency("5000.00");
    UUID accountB = fixture.accountWithItems(10);

    trade(service, accountB, accountA, "115.00");
    trade(service, accountA, accountB, "85.00");
    trade(service, accountB, accountA, "115.00");

    TrustedMarketSnapshot trusted = fixture.repository().inTransaction(tx ->
        tx.trustedMarketSnapshot("diamond-usd", Instant.EPOCH, Instant.EPOCH));
    MarketQuote quote = service.marketQuote(marketData);
    var candles = fixture.repository().loadCandles(
        "diamond-usd", Instant.EPOCH, Instant.now().plusSeconds(60));

    assertThat(fixture.tradeCount()).isEqualTo(3);
    assertThat(fixture.marketLastPrice()).isEqualByComparingTo("115.00");
    assertThat(quote.lastPrice()).isEqualByComparingTo("115.00");
    assertThat(quote.referencePrice()).isEqualByComparingTo("100.2487500000");
    assertThat(quote.liquidityTier()).isEqualTo(LiquidityTier.LOW);
    assertThat(quote.volume24h()).isEqualTo(15);
    assertThat(quote.status()).isEqualTo(MarketStatus.OPEN);
    assertThat(candles).extracting(Candle::volume)
        .satisfies(volumes -> assertThat(volumes.stream().mapToLong(Long::longValue).sum())
            .isEqualTo(15));
    assertThat(candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
        .isEqualByComparingTo("115.00");
    assertThat(candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow())
        .isEqualByComparingTo("85.00");
    assertThat(trusted.state().trustedPrice()).isEqualByComparingTo("100.2487500000");
    assertThat(trusted.influences()).hasSize(3);
    assertThat(trusted.influences().get(0).acceptedMove()).isEqualByComparingTo("0.005");
    assertThat(trusted.influences().get(1).acceptedMove()).isEqualByComparingTo("0.0025");
    assertThat(trusted.influences().get(2).acceptedMove()).isZero();
    assertThat(trusted.influences().getLast().reasons())
        .contains(LimitReason.LIMITED_BY_PAIR);
  }

  @Test
  void postCandleFailureRollsBackEverySettlementWrite() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(5);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(order(seller, OrderSide.SELL, "100.00"));
    PersistentOrderService failing = fixture.service((stage) -> {
      if (stage == SettlementStage.AFTER_RISK_UPDATE) {
        throw new IllegalStateException("forced post-candle failure");
      }
    }, RecoveryHandler.NO_OP);

    assertThatThrownBy(() -> failing.place(
        order(buyer, OrderSide.BUY, "100.00")))
        .isInstanceOf(RuntimeException.class);

    TrustedMarketSnapshot trusted = fixture.repository().inTransaction(tx ->
        tx.trustedMarketSnapshot("diamond-usd", Instant.EPOCH, Instant.EPOCH));
    assertThat(fixture.tradeCount()).isZero();
    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("1000.00");
    assertThat(fixture.frozenCurrency(buyer)).isZero();
    assertThat(fixture.availableItems(seller)).isZero();
    assertThat(fixture.frozenItems(seller)).isEqualTo(5L);
    assertThat(fixture.journalCount()).isZero();
    assertThat(trusted.state().lastMatchSequence()).isZero();
    assertThat(trusted.state().stateVersion()).isZero();
    assertThat(trusted.influences()).isEmpty();
    assertThat(fixture.repository().loadCandles(
        "diamond-usd", Instant.EPOCH, Instant.now().plusSeconds(60))).isEmpty();
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
  }

  private static void trade(
      PersistentOrderService service, UUID seller, UUID buyer, String price) throws SQLException {
    service.place(order(seller, OrderSide.SELL, price));
    service.place(order(buyer, OrderSide.BUY, price));
  }

  private static OrderRequest order(UUID account, OrderSide side, String price) {
    return new OrderRequest(UUID.randomUUID(), account, "diamond-usd", side,
        "LIMIT", new BigDecimal(price), null, 5);
  }
}
