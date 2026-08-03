package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartRendererTest {
  private final MarketChartRenderer renderer = new MarketChartRenderer();

  @Test
  void rendersSafePlaceholderForEmptySeriesAtRequestedDimensions() {
    MarketChartImage image = renderer.render(
        MarketChartSeries.empty(), MarketChartMode.KLINE, new MarketChartDimensions(2, 1));

    assertThat(image.width()).isEqualTo(256);
    assertThat(image.height()).isEqualTo(128);
    assertThat(image.pixels()).hasSize(256 * 128);
    assertThat(count(image, MarketChartPalette.NO_DATA)).isPositive();
  }

  @Test
  void rendersSingleFlatCandleWithoutDivisionByZero() {
    MarketChartSeries series = series(List.of(
        candle("10", "10", "10", "10")), "9.9", "10.1");

    MarketChartImage image = renderer.render(
        series, MarketChartMode.KLINE, new MarketChartDimensions(1, 1));

    assertThat(image.pixels()).hasSize(128 * 128);
    assertThat(count(image, MarketChartPalette.FLAT)).isPositive();
  }

  @Test
  void usesChineseMarketColorsForRisingAndFallingCandles() {
    MarketChartSeries series = series(List.of(
        candle("10", "13", "9", "12"),
        candle("12", "13", "8", "9")), "8", "13");

    MarketChartImage image = renderer.render(
        series, MarketChartMode.KLINE, new MarketChartDimensions(1, 1));

    assertThat(count(image, MarketChartPalette.RISE)).isPositive();
    assertThat(count(image, MarketChartPalette.FALL)).isPositive();
  }

  @Test
  void drawsWicksAcrossHighAndLowRange() {
    MarketChartSeries series = series(List.of(
        candle("10", "15", "5", "12")), "5", "15");

    MarketChartImage image = renderer.render(
        series, MarketChartMode.KLINE, new MarketChartDimensions(1, 1));

    int top = firstY(image, MarketChartPalette.RISE);
    int bottom = lastY(image, MarketChartPalette.RISE);
    assertThat(bottom - top).isGreaterThan(20);
  }

  @Test
  void rendersProfessionalGridAxesLatestPriceAndVolume() {
    MarketChartSeries series = new MarketChartSeries(List.of(
        candleAt("2026-07-30T00:00:00Z", "10", "12", "9", "11", 2),
        candleAt("2026-07-30T00:01:00Z", "11", "14", "10", "13", 8)),
        new BigDecimal("9"), new BigDecimal("14"));

    MarketChartImage image = renderer.render(series, MarketChartMode.KLINE,
        new MarketChartDimensions(2, 2), "DIAMOND", MarketChartPeriod.ONE_HOUR,
        new BigDecimal("13"), new BigDecimal("0.30"));

    assertThat(count(image, MarketChartPalette.GRID)).isPositive();
    assertThat(count(image, MarketChartPalette.AXIS_TEXT)).isPositive();
    assertThat(count(image, MarketChartPalette.LATEST_PRICE)).isPositive();
    assertThat(count(image, MarketChartPalette.VOLUME_RISE)).isPositive();
  }

  @Test
  void highlightsSingleLinePointInsteadOfDrawingOneInvisiblePixel() {
    MarketChartSeries series = series(List.of(
        candle("10", "10", "10", "10")), "9.9", "10.1");

    MarketChartImage image = renderer.render(series, MarketChartMode.LINE,
        new MarketChartDimensions(1, 1), "DIAMOND", MarketChartPeriod.ONE_HOUR,
        BigDecimal.TEN, BigDecimal.ZERO);

    assertThat(count(image, MarketChartPalette.HIGHLIGHT)).isGreaterThanOrEqualTo(5);
    assertThat(count(image, MarketChartPalette.LATEST_PRICE)).isPositive();
  }

  @Test
  void rendersLineModeUsingSegmentDirectionColors() {
    MarketChartSeries series = series(List.of(
        candle("10", "10", "10", "10"),
        candle("10", "12", "10", "12"),
        candle("12", "12", "9", "9")), "9", "12");

    MarketChartImage image = renderer.render(
        series, MarketChartMode.LINE, new MarketChartDimensions(1, 1));

    assertThat(count(image, MarketChartPalette.RISE)).isPositive();
    assertThat(count(image, MarketChartPalette.FALL)).isPositive();
  }

  @Test
  void canDisableProfessionalOverlaysForLegacyStyleRendering() {
    MarketChartRenderer minimalRenderer = new MarketChartRenderer(
        new MarketChartOptions(false, true, false, false));
    MarketChartSeries series = series(List.of(
        candle("10", "12", "9", "11")), "9", "12");

    MarketChartImage image = minimalRenderer.render(series, MarketChartMode.KLINE,
        new MarketChartDimensions(1, 1), "DIAMOND", MarketChartPeriod.ONE_HOUR,
        new BigDecimal("11"), new BigDecimal("0.10"));

    assertThat(count(image, MarketChartPalette.AXIS_TEXT)).isZero();
    assertThat(count(image, MarketChartPalette.LATEST_PRICE)).isZero();
    assertThat(count(image, MarketChartPalette.VOLUME_RISE)).isZero();
  }

  @Test
  void rendersFallingVolumeBarsInTheirOwnColor() {
    MarketChartSeries falling = series(List.of(
        candle("12", "12", "10", "10")), "10", "12");

    MarketChartImage image = renderer.render(falling, MarketChartMode.KLINE,
        new MarketChartDimensions(2, 1), "DIAMOND", MarketChartPeriod.ONE_HOUR,
        new BigDecimal("10"), new BigDecimal("-0.10"));

    assertThat(count(image, MarketChartPalette.VOLUME_FALL)).isPositive();
  }

  @Test
  void drawsPriceLabelsAtEveryHorizontalGridLine() {
    MarketChartImage image = renderer.render(series(List.of(
        candle("10", "12", "9", "11")), "9", "12"), MarketChartMode.KLINE,
        new MarketChartDimensions(2, 2), "DIAMOND", MarketChartPeriod.ONE_HOUR,
        new BigDecimal("11"), new BigDecimal("0.10"));
    MarketChartLayout.Rect plot = MarketChartLayout.forDimensions(
        new MarketChartDimensions(2, 2)).plot();
    int axisLeft = plot.right() + 1;

    for (int fraction = 1; fraction < 4; fraction++) {
      int gridY = plot.top() + fraction * (plot.height() - 1) / 4;
      assertThat(axisTextAt(image, axisLeft, gridY)).as("label at grid " + fraction + "/4")
          .isPositive();
    }
  }

  @Test
  void formatsExtremePricesWithinTheTinyPixelAxisBudget() {
    assertThat(MarketChartRenderer.compactPrice(new BigDecimal("123456789")))
        .isEqualTo("1.23E8");
    assertThat(MarketChartRenderer.compactPrice(new BigDecimal("0.0000001234")))
        .isEqualTo("1.23E-7");
  }

  @Test
  void fallsBackToMarketLabelWhenDisplayNameHasNoAsciiGlyphs() {
    assertThat(MarketChartRenderer.compactText("钻石市场", 8)).isEqualTo("MARKET");
  }

  @Test
  void formatsAxisTimesInLocalZoneWithDateForMultiDayCharts() {
    ZoneId shanghai = ZoneId.of("Asia/Shanghai");
    ChartCandle eveningUtc = candleAt("2026-07-30T16:00:00Z", "10", "12", "9", "11", 1);
    ChartCandle nextDay = candleAt("2026-07-31T15:00:00Z", "10", "12", "9", "11", 1);

    assertThat(MarketChartRenderer.timeLabel(eveningUtc, shanghai, false))
        .isEqualTo("00:00");
    assertThat(MarketChartRenderer.timeLabel(eveningUtc, shanghai, true))
        .isEqualTo("07-31 00:00");
    assertThat(MarketChartRenderer.timeLabel(nextDay, shanghai, true))
        .isEqualTo("07-31 23:00");
  }

  private static MarketChartSeries series(List<ChartCandle> candles, String min, String max) {
    return new MarketChartSeries(candles, new BigDecimal(min), new BigDecimal(max));
  }

  private static ChartCandle candle(String open, String high, String low, String close) {
    return candleAt("2026-07-30T00:00:00Z", open, high, low, close, 1L);
  }

  private static ChartCandle candleAt(String at, String open, String high, String low,
                                      String close, long volume) {
    return new ChartCandle(Instant.parse(at), new BigDecimal(open), new BigDecimal(high),
        new BigDecimal(low), new BigDecimal(close), volume,
        new BigDecimal(close).multiply(BigDecimal.valueOf(volume)));
  }

  private static long count(MarketChartImage image, byte color) {
    long count = 0;
    for (byte pixel : image.pixels()) {
      if (pixel == color) {
        count++;
      }
    }
    return count;
  }

  private static long axisTextAt(MarketChartImage image, int fromX, int y) {
    long count = 0;
    for (int x = fromX; x < image.width(); x++) {
      if (image.pixels()[y * image.width() + x] == MarketChartPalette.AXIS_TEXT) {
        count++;
      }
    }
    return count;
  }

  private static int firstY(MarketChartImage image, byte color) {
    for (int y = 0; y < image.height(); y++) {
      for (int x = 0; x < image.width(); x++) {
        if (image.pixel(x, y) == color) {
          return y;
        }
      }
    }
    return -1;
  }

  private static int lastY(MarketChartImage image, byte color) {
    for (int y = image.height() - 1; y >= 0; y--) {
      for (int x = 0; x < image.width(); x++) {
        if (image.pixel(x, y) == color) {
          return y;
        }
      }
    }
    return -1;
  }
}
