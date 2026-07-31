package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartSeriesBuilderV2Test {
  private final MarketChartSeriesBuilder builder = new MarketChartSeriesBuilder();

  @Test
  void honorsAnExplicitFixedIntervalInsteadOfAdaptiveSelection() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    List<Candle> candles = java.util.stream.IntStream.range(0, 13)
        .mapToObj(index -> candle(start.plusSeconds(index * 300L), "10", "10", "10", "10", 1))
        .toList();

    MarketChartSeries result = new MarketChartSeriesBuilder(MarketChartInterval.FIVE_MINUTES)
        .build(candles, List.of(), new MarketChartDimensions(1, 1),
            MarketChartPeriod.ONE_DAY, LiquidityTier.LOW);

    assertThat(result.interval()).isEqualTo(MarketChartInterval.FIVE_MINUTES);
    assertThat(result.candles()).hasSizeLessThanOrEqualTo(12);
  }

  @Test
  void preservesRawExtremesAndAddsTrustedLine() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    List<Candle> raw = List.of(
        candle(start, "100", "115.00", "95", "110", 2, "220"),
        candle(start.plusSeconds(300), "110", "111", "80", "85.00", 3, "255"));
    List<TrustedPricePoint> trusted = List.of(
        new TrustedPricePoint(start, bd("100.50")),
        new TrustedPricePoint(start.plusSeconds(300), bd("100.24875")));

    MarketChartSeries result = builder.build(raw, trusted,
        new MarketChartDimensions(2, 1), MarketChartPeriod.ONE_DAY, LiquidityTier.STABLE);

    assertThat(result.candles()).extracting(ChartCandle::high).contains(bd("115.00"));
    assertThat(result.trustedPoints()).extracting(TrustedPricePoint::price)
        .containsExactly(bd("100.50"), bd("100.24875"));
    assertThat(result.latestRawPrice()).isEqualByComparingTo("85.00");
    assertThat(result.latestTrustedPrice()).isEqualByComparingTo("100.24875");
    assertThat(result.liquidityTier()).isEqualTo(LiquidityTier.STABLE);
    assertThat(result.minimum()).isEqualByComparingTo("80");
    assertThat(result.maximum()).isEqualByComparingTo("115.00");
  }

  @Test
  void marksLongNoTradePeriodWithoutSyntheticCandles() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    MarketChartSeries result = builder.build(List.of(
            candle(start, "100", "100", "100", "100", 1, "100"),
            candle(start.plusSeconds(3600), "101", "101", "101", "101", 1, "101")),
        List.of(), new MarketChartDimensions(1, 1), MarketChartPeriod.ONE_DAY,
        LiquidityTier.LOW);

    assertThat(result.candles()).hasSize(2);
    assertThat(result.gaps()).containsExactly(new ChartGap(start, start.plusSeconds(3600)));
  }

  @Test
  void keepsMaintenancePointOutOfCandleVolume() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    MarketChartSeries result = builder.build(
        List.of(candle(start, "100", "100", "100", "100", 7, "700")),
        List.of(new TrustedPricePoint(start.plusSeconds(60), bd("99.5"))),
        new MarketChartDimensions(1, 1), MarketChartPeriod.ONE_HOUR,
        LiquidityTier.GROWING);

    assertThat(result.candles()).singleElement().extracting(ChartCandle::volume).isEqualTo(7L);
    assertThat(result.trustedPoints()).containsExactly(
        new TrustedPricePoint(start.plusSeconds(60), bd("99.5")));
  }

  @Test
  void laterLiveMinuteOverridesPersistedMinuteOnce() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    Candle persisted = candle(start, "100", "101", "99", "100", 2, "200");
    Candle live = candle(start, "100", "105", "98", "104", 7, "728");

    MarketChartSeries result = builder.build(List.of(persisted, live), List.of(),
        new MarketChartDimensions(1, 1), MarketChartPeriod.ONE_HOUR, LiquidityTier.LOW);

    assertThat(result.candles()).containsExactly(new ChartCandle(start, bd("100"), bd("105"),
        bd("98"), bd("104"), 7, bd("728")));
  }

  @Test
  void padsFlatTrustedOnlySeriesAndSortsReferencePoints() {
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    MarketChartSeries result = builder.build(List.of(), List.of(
            new TrustedPricePoint(start.plusSeconds(60), bd("10.00")),
            new TrustedPricePoint(start, bd("10.00"))),
        new MarketChartDimensions(1, 1), MarketChartPeriod.ONE_HOUR, LiquidityTier.LOW);

    assertThat(result.trustedPoints()).extracting(TrustedPricePoint::at)
        .containsExactly(start, start.plusSeconds(60));
    assertThat(result.flat()).isTrue();
    assertThat(result.minimum()).isLessThan(bd("10.00"));
    assertThat(result.maximum()).isGreaterThan(bd("10.00"));
    assertThat(result.hasData()).isTrue();
  }

  private static Candle candle(Instant start, String open, String high, String low, String close,
                               long volume, String notional) {
    return new Candle("minecraft_diamond/default", start, bd(open), bd(high), bd(low), bd(close),
        volume, bd(notional));
  }

  private static Candle candle(Instant start, String open, String high, String low, String close,
                               long volume) {
    return candle(start, open, high, low, close, volume, close);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
