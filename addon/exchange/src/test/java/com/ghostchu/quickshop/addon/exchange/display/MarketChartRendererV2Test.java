package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartRendererV2Test {
  private final MarketChartRenderer renderer = new MarketChartRenderer();

  @Test
  void rendersExactCanvasForEverySupportedDimension() {
    for (MarketChartDimensions dimensions : dimensions()) {
      MarketChartImage image = renderer.render(MarketChartSeries.empty(), MarketChartMode.KLINE,
          dimensions);

      assertThat(image.width()).isEqualTo(dimensions.pixelWidth());
      assertThat(image.height()).isEqualTo(dimensions.pixelHeight());
      assertThat(image.pixels()).hasSize(dimensions.pixelWidth() * dimensions.pixelHeight());
      assertThat(count(image, MarketChartPalette.BACKGROUND)).isPositive();
      assertThat(count(image, MarketChartPalette.AXIS_TEXT)).isPositive();
      assertThat(count(image, MarketChartPalette.NO_DATA)).isPositive();
    }
  }

  @Test
  void drawsTrustedReferenceOnlyWhenReferencePointsExist() {
    MarketChartImage withTrusted = renderer.render(series(true, false), MarketChartMode.KLINE,
        new MarketChartDimensions(2, 2));
    MarketChartImage rawOnly = renderer.render(series(false, false), MarketChartMode.KLINE,
        new MarketChartDimensions(2, 2));

    assertThat(count(withTrusted, MarketChartPalette.TRUSTED_REFERENCE)).isPositive();
    assertThat(count(rawOnly, MarketChartPalette.TRUSTED_REFERENCE)).isZero();
    assertThat(count(withTrusted, MarketChartPalette.RISE)).isPositive();
    assertThat(count(withTrusted, MarketChartPalette.FALL)).isPositive();
    assertThat(count(withTrusted, MarketChartPalette.FLAT)).isPositive();
  }

  @Test
  void marksSparseGapWithoutAddingCandlePixels() {
    MarketChartImage withGap = renderer.render(series(true, true), MarketChartMode.KLINE,
        new MarketChartDimensions(2, 1));
    MarketChartImage withoutGap = renderer.render(series(true, false), MarketChartMode.KLINE,
        new MarketChartDimensions(2, 1));

    assertThat(count(withGap, MarketChartPalette.GAP_MARKER)).isPositive();
    assertThat(count(withoutGap, MarketChartPalette.GAP_MARKER)).isZero();
  }

  @Test
  void singleFlatCandleGetsVisibleRangeMarker() {
    Instant at = Instant.parse("2026-07-31T00:00:00Z");
    ChartCandle candle = candle(at, "10", "10", "10", "10", 1);
    MarketChartSeries series = new MarketChartSeries(MarketChartInterval.FIVE_MINUTES,
        List.of(candle), List.of(new TrustedPricePoint(at, bd("10"))), List.of(), bd("9.9"),
        bd("10.1"), bd("10"), bd("10"), LiquidityTier.LOW, true, true);

    MarketChartImage image = renderer.render(series, MarketChartMode.KLINE,
        new MarketChartDimensions(1, 1), "DIAMOND", MarketChartPeriod.ONE_HOUR, bd("10"),
        BigDecimal.ZERO);

    assertThat(count(image, MarketChartPalette.FLAT)).isPositive();
    assertThat(count(image, MarketChartPalette.HIGHLIGHT)).isGreaterThanOrEqualTo(5);
    assertThat(count(image, MarketChartPalette.TRUSTED_REFERENCE)).isPositive();
  }

  @Test
  void keepsExtremePricesAndStableReferenceVisible() {
    Instant at = Instant.parse("2026-07-31T00:00:00Z");
    MarketChartSeries series = new MarketChartSeries(MarketChartInterval.FIVE_MINUTES,
        List.of(candle(at, "0.0000001", "123456789", "0.0000001", "100", 4)),
        List.of(new TrustedPricePoint(at, bd("10"))), List.of(), bd("0.0000001"),
        bd("123456789"), bd("100"), bd("10"), LiquidityTier.GROWING, false, true);

    MarketChartImage image = renderer.render(series, MarketChartMode.KLINE,
        new MarketChartDimensions(2, 2), "EXTREME", MarketChartPeriod.SEVEN_DAYS, bd("100"),
        BigDecimal.ZERO);

    assertThat(count(image, MarketChartPalette.TRUSTED_REFERENCE)).isPositive();
    assertThat(count(image, MarketChartPalette.AXIS_TEXT)).isPositive();
  }

  @Test
  void rendersGlyphsNeededByMarketLegendAndConfidenceLabels() {
    String labels = "OSBWFG";
    Set<Integer> pixels = new HashSet<>();
    PixelFont.draw((x, y, color) -> pixels.add(y * 128 + x), labels, 0, 0,
        MarketChartPalette.AXIS_TEXT);

    for (int index = 0; index < labels.length(); index++) {
      int left = index * 4;
      assertThat(pixels).anyMatch(pixel -> pixel % 128 >= left && pixel % 128 < left + 3);
    }
  }

  @Test
  void writesReviewArtifactsWhenRequested() throws IOException {
    if (!Boolean.getBoolean("chart.review.output")) {
      return;
    }
    Path output = Path.of(System.getProperty("user.dir"), "..", "..", "outputs",
        "chart-v2-review").normalize();
    Files.createDirectories(output);
    for (MarketChartDimensions dimensions : dimensions()) {
      MarketChartImage image = renderer.render(series(true, true), MarketChartMode.KLINE,
          dimensions, "DIAMOND", MarketChartPeriod.ONE_DAY, bd("85"), bd("-0.15"));
      Files.writeString(output.resolve(dimensions.columns() + "x" + dimensions.rows() + ".ppm"),
          ppm(image));
    }
  }

  private static MarketChartSeries series(boolean trusted, boolean gap) {
    Instant first = Instant.parse("2026-07-31T00:00:00Z");
    Instant second = gap ? first.plusSeconds(3600) : first.plusSeconds(300);
    Instant third = second.plusSeconds(300);
    List<ChartCandle> candles = List.of(
        candle(first, "100", "115", "95", "110", 2),
        candle(second, "110", "112", "80", "85", 5),
        candle(third, "85", "90", "85", "85", 1));
    List<TrustedPricePoint> references = trusted ? List.of(
        new TrustedPricePoint(first, bd("100.5")),
        new TrustedPricePoint(third, bd("100.25"))) : List.of();
    List<ChartGap> gaps = gap ? List.of(new ChartGap(first, second)) : List.of();
    return new MarketChartSeries(MarketChartInterval.FIVE_MINUTES, candles, references, gaps,
        bd("80"), bd("115"), bd("85"), trusted ? bd("100.25") : BigDecimal.ZERO,
        LiquidityTier.STABLE, false, false);
  }

  private static ChartCandle candle(Instant at, String open, String high, String low, String close,
                                    long volume) {
    return new ChartCandle(at, bd(open), bd(high), bd(low), bd(close), volume,
        bd(close).multiply(BigDecimal.valueOf(volume)));
  }

  private static List<MarketChartDimensions> dimensions() {
    return List.of(new MarketChartDimensions(1, 1), new MarketChartDimensions(2, 1),
        new MarketChartDimensions(2, 2));
  }

  private static long count(MarketChartImage image, byte color) {
    long result = 0;
    for (byte pixel : image.pixels()) {
      if (pixel == color) result++;
    }
    return result;
  }

  private static String ppm(MarketChartImage image) {
    StringBuilder result = new StringBuilder("P3\n")
        .append(image.width()).append(' ').append(image.height()).append("\n255\n");
    for (byte pixel : image.pixels()) {
      int[] rgb = rgb(pixel);
      result.append(rgb[0]).append(' ').append(rgb[1]).append(' ').append(rgb[2]).append('\n');
    }
    return result.toString();
  }

  private static int[] rgb(byte pixel) {
    if (pixel == MarketChartPalette.BACKGROUND) return new int[] {20, 24, 30};
    if (pixel == MarketChartPalette.GRID) return new int[] {52, 59, 68};
    if (pixel == MarketChartPalette.RISE) return new int[] {225, 68, 68};
    if (pixel == MarketChartPalette.FALL) return new int[] {53, 186, 111};
    if (pixel == MarketChartPalette.TRUSTED_REFERENCE) return new int[] {245, 190, 58};
    if (pixel == MarketChartPalette.GAP_MARKER) return new int[] {136, 146, 160};
    int value = Byte.toUnsignedInt(pixel) * 2;
    return new int[] {Math.min(value, 255), Math.min(value, 255), Math.min(value, 255)};
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
