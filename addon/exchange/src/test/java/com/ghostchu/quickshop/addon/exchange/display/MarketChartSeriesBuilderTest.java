package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketChartSeriesBuilderTest {
  @Test
  void parsesSupportedModesPeriodsAndDimensions() {
    assertThat(MarketChartMode.parse("kline")).isEqualTo(MarketChartMode.KLINE);
    assertThat(MarketChartMode.parse("LINE")).isEqualTo(MarketChartMode.LINE);
    assertThat(MarketChartPeriod.parse("24h").duration()).isEqualTo(Duration.ofHours(24));
    assertThat(MarketChartDimensions.parse("2x1"))
        .isEqualTo(new MarketChartDimensions(2, 1));
    assertThat(MarketChartDimensions.parse("2x1").pixelWidth()).isEqualTo(256);
    assertThat(MarketChartDimensions.parse("2x2").pixelHeight()).isEqualTo(256);

    assertThatThrownBy(() -> MarketChartMode.parse("bars"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MarketChartPeriod.parse("30d"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MarketChartDimensions.parse("3x3"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sortsAndAggregatesCandlesIntoBoundedChronologicalBuckets() {
    Instant start = Instant.parse("2026-07-30T00:00:00Z");
    List<Candle> candles = List.of(
        candle(start.plusSeconds(180), "13", "16", "12", "15", 4, "60"),
        candle(start, "10", "12", "9", "11", 1, "11"),
        candle(start.plusSeconds(60), "11", "14", "10", "13", 2, "26"),
        candle(start.plusSeconds(120), "13", "15", "8", "12", 3, "36"));

    MarketChartSeries series = new MarketChartSeriesBuilder().build(candles, 2);

    assertThat(series.candles()).containsExactly(
        new ChartCandle(start, new BigDecimal("10"), new BigDecimal("14"),
            new BigDecimal("9"), new BigDecimal("13"), 3, new BigDecimal("37")),
        new ChartCandle(start.plusSeconds(120), new BigDecimal("13"), new BigDecimal("16"),
            new BigDecimal("8"), new BigDecimal("15"), 7, new BigDecimal("96")));
    assertThat(series.minimumPrice()).isEqualByComparingTo("8");
    assertThat(series.maximumPrice()).isEqualByComparingTo("16");
  }

  @Test
  void returnsEmptySeriesForNoCandles() {
    MarketChartSeries series = new MarketChartSeriesBuilder().build(List.of(), 32);

    assertThat(series.candles()).isEmpty();
    assertThat(series.hasData()).isFalse();
  }

  @Test
  void givesFlatAndSinglePointSeriesANonZeroPriceRange() {
    Instant start = Instant.parse("2026-07-30T00:00:00Z");

    MarketChartSeries series = new MarketChartSeriesBuilder().build(
        List.of(candle(start, "10", "10", "10", "10", 1, "10")), 32);

    assertThat(series.hasData()).isTrue();
    assertThat(series.minimumPrice()).isLessThan(new BigDecimal("10"));
    assertThat(series.maximumPrice()).isGreaterThan(new BigDecimal("10"));
    assertThat(series.maximumPrice().subtract(series.minimumPrice())).isPositive();
  }

  @Test
  void rejectsInvalidCandleLimit() {
    assertThatThrownBy(() -> new MarketChartSeriesBuilder().build(List.of(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Candle candle(Instant start, String open, String high, String low, String close,
                               long volume, String notional) {
    return new Candle("minecraft_diamond/default", start,
        new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
        volume, new BigDecimal(notional));
  }
}
