package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMarketDisplayDataSourceV2Test {
  @Test
  void loadsTrustedPointsAndStateMetadataWithoutChangingRawCandleVolume() throws Exception {
    Instant now = Instant.parse("2026-07-31T12:00:00Z");
    Candle raw = new Candle("diamond", now.minusSeconds(60), bd("100"), bd("100"), bd("100"),
        bd("100"), 7, bd("700"));
    TrustedPriceState state = new TrustedPriceState("diamond", bd("101"), bd("101"), now,
        LiquidityTier.STABLE, 1, 4, 9);
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "Diamond", () -> quote(now), (from, to) -> List.of(raw),
              (from, to) -> List.of(),
              (from, to) -> List.of(new TrustedPricePoint(now.minusSeconds(60), bd("100")),
                  new TrustedPricePoint(now, bd("101"))),
              () -> state)), executor);

      MarketDisplaySnapshot snapshot = source.snapshot(
          "diamond", MarketChartPeriod.ONE_HOUR, now).get(5, TimeUnit.SECONDS);

      assertThat(snapshot.candles()).containsExactly(raw);
      assertThat(snapshot.candles().getFirst().volume()).isEqualTo(7);
      assertThat(snapshot.trustedPoints()).extracting(TrustedPricePoint::price)
          .containsExactly(bd("100"), bd("101"));
      assertThat(snapshot.liquidityTier()).isEqualTo(LiquidityTier.STABLE);
      assertThat(snapshot.trustedStateVersion()).isEqualTo(9);
    }
  }

  @Test
  void overridesStaleTrustedPointAtTheEvaluationInstant() throws Exception {
    Instant now = Instant.parse("2026-07-31T12:00:00Z");
    Instant stateAt = now.minusNanos(1);
    TrustedPriceState state = new TrustedPriceState("diamond", bd("101"), bd("101"), now,
        LiquidityTier.STABLE, 1, 4, 9);
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "Diamond", () -> quote(now), (from, to) -> List.of(),
              (from, to) -> List.of(),
              (from, to) -> List.of(new TrustedPricePoint(stateAt, bd("100"))),
              () -> state)), executor);

      MarketDisplaySnapshot snapshot = source.snapshot(
          "diamond", MarketChartPeriod.ONE_HOUR, now).get(5, TimeUnit.SECONDS);

      assertThat(snapshot.trustedPoints())
          .extracting(TrustedPricePoint::price)
          .containsExactly(bd("101"));
      assertThat(snapshot.trustedPoints().getFirst().at()).isEqualTo(stateAt);
    }
  }

  private static MarketQuote quote(Instant now) {
    return new MarketQuote("diamond", bd("100"), bd("101"), LiquidityTier.STABLE,
        bd("99"), bd("102"), bd("0"), 7, bd("700"), MarketStatus.OPEN, now);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
