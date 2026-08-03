package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Pure Java professional market chart renderer for vanilla map pixels. */
public final class MarketChartRenderer {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
  private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
  private final MarketChartOptions options;
  private final ZoneId zone;

  public MarketChartRenderer() {
    this(MarketChartOptions.defaults(), ZoneId.systemDefault());
  }

  public MarketChartRenderer(MarketChartOptions options) {
    this(options, ZoneId.systemDefault());
  }

  MarketChartRenderer(MarketChartOptions options, ZoneId zone) {
    this.options = Objects.requireNonNull(options, "options");
    this.zone = Objects.requireNonNull(zone, "zone");
  }

  public String optionsFingerprint() {
    return options.fingerprint();
  }

  public MarketChartImage render(MarketChartSeries series, MarketChartMode mode,
                                 MarketChartDimensions dimensions) {
    BigDecimal latest = series.candles().isEmpty()
        ? BigDecimal.ZERO : series.candles().getLast().close();
    return render(series, mode, dimensions, "MARKET", "MARKET", MarketChartPeriod.ONE_DAY,
        latest, BigDecimal.ZERO);
  }

  public MarketChartImage render(MarketChartSeries series, MarketChartMode mode,
                                 MarketChartDimensions dimensions, String displayName,
                                 MarketChartPeriod period, BigDecimal latestPrice,
                                 BigDecimal change) {
    return render(series, mode, dimensions, displayName, displayName, period, latestPrice,
        change);
  }

  public MarketChartImage render(MarketChartSeries series, MarketChartMode mode,
                                 MarketChartDimensions dimensions, String displayName,
                                 String marketId, MarketChartPeriod period,
                                 BigDecimal latestPrice, BigDecimal change) {
    Objects.requireNonNull(series, "series");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(dimensions, "dimensions");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(latestPrice, "latestPrice");
    Objects.requireNonNull(change, "change");
    Canvas canvas = new Canvas(dimensions.pixelWidth(), dimensions.pixelHeight());
    canvas.fill(MarketChartPalette.BACKGROUND);
    MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);
    BigDecimal primaryLatest = series.trustedPoints().isEmpty()
        ? latestPrice : series.latestTrustedPrice();
    if (options.professionalLayout()) {
      drawHeader(canvas, layout, displayName, marketId, mode, period, series.interval(),
          primaryLatest, change);
    }
    drawFrameAndGrid(canvas, layout);
    if (!series.hasData()) {
      drawNoData(canvas, layout.plot());
      return canvas.image();
    }
    int[] positions = candlePositions(layout.plot(), series.candles(), series.gaps());
    if (options.showGapMarkers()) {
      drawGapMarkers(canvas, layout.plot(), series, positions);
    }
    if (!series.candles().isEmpty()) {
      if (mode == MarketChartMode.KLINE) {
        drawCandles(canvas, layout.plot(), series, positions);
      } else {
        drawLine(canvas, layout.plot(), series, positions);
      }
    }
    if (options.showTrustedPriceLine() && !series.trustedPoints().isEmpty()) {
      drawTrustedReference(canvas, layout.plot(), series, positions);
    }
    if (options.showLatestPriceLine()) {
      if (series.trustedPoints().isEmpty()) {
        drawLatestPrice(canvas, layout, series, latestPrice);
      } else {
        drawPriceLabel(canvas, layout, series, series.latestTrustedPrice(),
            MarketChartPalette.TRUSTED_REFERENCE);
        if (!series.candles().isEmpty()) {
          drawRawLatestMarker(canvas, layout, series);
        }
      }
    }
    if (options.professionalLayout()) {
      layout.priceAxis().ifPresent(axis -> drawPriceAxis(canvas, layout.plot(), axis, series));
      if (!series.candles().isEmpty()) {
        layout.timeAxis().ifPresent(axis -> drawTimeAxis(canvas, axis, series.candles()));
      }
      drawLegendAndConfidence(canvas, layout, series);
    }
    if (options.showVolume()) {
      layout.volume().ifPresent(
          area -> drawVolume(canvas, area, layout.plot(), series.candles(), positions));
    }
    return canvas.image();
  }

  private static void drawHeader(Canvas canvas, MarketChartLayout layout, String displayName,
                                 String marketId,
                                 MarketChartMode mode, MarketChartPeriod period,
                                 MarketChartInterval interval,
                                 BigDecimal latestPrice, BigDecimal change) {
    String market = compactText(displayName, marketId,
        layout.density() == MarketChartLayout.Density.COMPACT ? 8 : 16);
    PixelFont.draw(canvas::set, market, layout.header().left(), layout.header().top(),
        MarketChartPalette.AXIS_TEXT);
    String modeAndPeriod = layout.density() == MarketChartLayout.Density.COMPACT
        ? (mode == MarketChartMode.KLINE ? "K" : "L") + " " + interval.label()
        : (mode == MarketChartMode.KLINE ? "K" : "LINE") + " " + interval.label()
            + " " + period.token();
    int rightX = layout.header().right() - PixelFont.width(modeAndPeriod) + 1;
    PixelFont.draw(canvas::set, modeAndPeriod, Math.max(layout.header().left(), rightX),
        layout.header().top(), MarketChartPalette.AXIS_TEXT);
    if (layout.density() == MarketChartLayout.Density.COMPACT) {
      PixelFont.draw(canvas::set, compactPrice(latestPrice), layout.header().left(),
          layout.header().top() + 6, MarketChartPalette.HIGHLIGHT);
    } else if (layout.density() == MarketChartLayout.Density.FULL) {
      String summary = compactPrice(latestPrice) + " " + percent(change);
      PixelFont.draw(canvas::set, summary, layout.header().left(), layout.header().top() + 7,
          change.signum() > 0 ? MarketChartPalette.RISE
              : change.signum() < 0 ? MarketChartPalette.FALL : MarketChartPalette.FLAT);
    }
  }

  private static void drawFrameAndGrid(Canvas canvas, MarketChartLayout layout) {
    MarketChartLayout.Rect plot = layout.plot();
    canvas.rectangle(plot, MarketChartPalette.BORDER);
    for (int fraction = 1; fraction < 4; fraction++) {
      int y = plot.top() + fraction * (plot.height() - 1) / 4;
      canvas.dashedHorizontal(plot.left() + 1, plot.right() - 1, y, MarketChartPalette.GRID, 2);
    }
    for (int fraction = 1; fraction < 4; fraction++) {
      int x = plot.left() + fraction * (plot.width() - 1) / 4;
      canvas.dashedVertical(x, plot.top() + 1, plot.bottom() - 1, MarketChartPalette.GRID, 2);
    }
  }

  private static void drawNoData(Canvas canvas, MarketChartLayout.Rect plot) {
    int centerX = plot.left() + plot.width() / 2;
    int centerY = plot.top() + plot.height() / 2;
    canvas.horizontal(centerX - 10, centerX + 10, centerY, MarketChartPalette.NO_DATA);
    canvas.vertical(centerX, centerY - 4, centerY + 4, MarketChartPalette.NO_DATA);
  }

  private static void drawCandles(Canvas canvas, MarketChartLayout.Rect plot,
                                  MarketChartSeries series, int[] positions) {
    List<ChartCandle> candles = series.candles();
    int effectiveSlots = candles.size() + matchingGapCount(candles, series.gaps());
    int bodyWidth = Math.min(7, Math.max(1, (plot.width() - 4) / effectiveSlots - 2));
    int bodyHalfWidth = bodyWidth / 2;
    for (int index = 0; index < candles.size(); index++) {
      ChartCandle candle = candles.get(index);
      int x = positions[index];
      byte color = directionColor(candle.open(), candle.close());
      canvas.vertical(x, priceToY(plot, series, candle.high()),
          priceToY(plot, series, candle.low()), color);
      int openY = priceToY(plot, series, candle.open());
      int closeY = priceToY(plot, series, candle.close());
      int bodyTop = Math.min(openY, closeY);
      int bodyBottom = Math.max(openY, closeY);
      if (bodyTop == bodyBottom) {
        canvas.horizontal(x - bodyHalfWidth, x + bodyHalfWidth, bodyTop, color);
      } else {
        for (int y = bodyTop; y <= bodyBottom; y++) {
          canvas.horizontal(x - bodyHalfWidth, x + bodyHalfWidth, y, color);
        }
      }
    }
    if (series.singleCandle()) {
      drawPointMarker(canvas, positions[0], priceToY(plot, series, candles.getFirst().close()),
          MarketChartPalette.HIGHLIGHT);
    }
  }

  private static void drawLine(Canvas canvas, MarketChartLayout.Rect plot,
                               MarketChartSeries series, int[] positions) {
    List<ChartCandle> candles = series.candles();
    if (candles.size() == 1) {
      int x = positions[0];
      int y = priceToY(plot, series, candles.getFirst().close());
      drawPointMarker(canvas, x, y, MarketChartPalette.HIGHLIGHT);
      return;
    }
    int previousX = positions[0];
    int previousY = priceToY(plot, series, candles.getFirst().close());
    for (int index = 1; index < candles.size(); index++) {
      int x = positions[index];
      int y = priceToY(plot, series, candles.get(index).close());
      if (!hasGap(candles.get(index - 1), candles.get(index), series.gaps())) {
        byte color = directionColor(candles.get(index - 1).close(), candles.get(index).close());
        canvas.line(previousX, previousY, x, y, color);
      }
      previousX = x;
      previousY = y;
    }
    canvas.set(previousX, previousY, MarketChartPalette.HIGHLIGHT);
  }

  private static void drawLatestPrice(Canvas canvas, MarketChartLayout layout,
                                      MarketChartSeries series, BigDecimal latestPrice) {
    int y = priceToY(layout.plot(), series, latestPrice);
    canvas.dashedHorizontal(layout.plot().left() + 1, layout.plot().right() - 1, y,
        MarketChartPalette.LATEST_PRICE, 3);
    drawPriceLabel(canvas, layout, series, latestPrice, MarketChartPalette.LATEST_PRICE);
  }

  private static void drawPriceAxis(Canvas canvas, MarketChartLayout.Rect plot,
                                    MarketChartLayout.Rect priceAxis,
                                    MarketChartSeries series) {
    BigDecimal range = series.maximumPrice().subtract(series.minimumPrice());
    for (int fraction = 0; fraction <= 4; fraction++) {
      BigDecimal price = series.maximumPrice().subtract(range.multiply(
          BigDecimal.valueOf(fraction)).divide(BigDecimal.valueOf(4), 8, RoundingMode.HALF_UP));
      int y = priceLabelY(plot, fraction);
      String label = trimToPixels(compactPrice(price), priceAxis.width());
      PixelFont.draw(canvas::set, label, priceAxis.left() + 1,
          Math.min(priceAxis.bottom() - 4, y), MarketChartPalette.AXIS_TEXT);
    }
  }

  static int priceLabelY(MarketChartLayout.Rect plot, int fraction) {
    if (fraction < 0 || fraction > 4) {
      throw new IllegalArgumentException("price axis fraction must be between 0 and 4");
    }
    return plot.top() + fraction * (plot.height() - 1) / 4;
  }

  private static void drawVolume(Canvas canvas, MarketChartLayout.Rect area,
                                 MarketChartLayout.Rect plot, List<ChartCandle> candles,
                                 int[] positions) {
    canvas.rectangle(area, MarketChartPalette.BORDER);
    long maximum = candles.stream().mapToLong(ChartCandle::volume).max().orElse(0L);
    if (maximum <= 0) {
      return;
    }
    int barHalfWidth = Math.max(1, Math.min(3, (area.width() - 4) / candles.size() / 3));
    for (int index = 0; index < candles.size(); index++) {
      ChartCandle candle = candles.get(index);
      int x = area.left() + 2 + (positions[index] - plot.left() - 2)
          * Math.max(1, area.width() - 4) / Math.max(1, plot.width() - 4);
      int barHeight = Math.max(1, (int) Math.round(
          (double) candle.volume() / maximum * Math.max(1, area.height() - 3)));
      byte color = candle.close().compareTo(candle.open()) >= 0
          ? MarketChartPalette.VOLUME_RISE : MarketChartPalette.VOLUME_FALL;
      for (int y = area.bottom() - 1; y >= area.bottom() - barHeight; y--) {
        canvas.horizontal(x - barHalfWidth, x + barHalfWidth, y, color);
      }
    }
  }

  private void drawTimeAxis(Canvas canvas, MarketChartLayout.Rect area,
                                   List<ChartCandle> candles) {
    ChartCandle first = candles.getFirst();
    ChartCandle last = candles.getLast();
    boolean multiDay = first.bucketStart().atZone(zone).toLocalDate()
        .isBefore(last.bucketStart().atZone(zone).toLocalDate());
    String left = timeLabel(first, zone, multiDay);
    String right = timeLabel(last, zone, multiDay);
    PixelFont.draw(canvas::set, left, area.left(), area.top() + 2, MarketChartPalette.AXIS_TEXT);
    PixelFont.draw(canvas::set, right,
        Math.max(area.left(), area.right() - PixelFont.width(right) + 1), area.top() + 2,
        MarketChartPalette.AXIS_TEXT);
  }

  static String timeLabel(ChartCandle candle, ZoneId zone, boolean multiDay) {
    var local = candle.bucketStart().atZone(zone);
    return (multiDay ? DATE_TIME : TIME).format(local);
  }

  private static int[] candlePositions(MarketChartLayout.Rect plot, List<ChartCandle> candles,
                                       List<ChartGap> gaps) {
    int[] positions = new int[candles.size()];
    if (candles.isEmpty()) {
      return positions;
    }
    int slots = candles.size() + matchingGapCount(candles, gaps);
    int left = plot.left() + 2;
    int usable = Math.max(1, plot.width() - 4);
    int slot = 0;
    for (int index = 0; index < candles.size(); index++) {
      if (index > 0 && hasGap(candles.get(index - 1), candles.get(index), gaps)) {
        slot++;
      }
      positions[index] = Math.min(plot.right() - 2,
          left + (int) Math.floor((slot + 0.5) * usable / slots));
      slot++;
    }
    return positions;
  }

  private static int matchingGapCount(List<ChartCandle> candles, List<ChartGap> gaps) {
    int result = 0;
    for (int index = 1; index < candles.size(); index++) {
      if (hasGap(candles.get(index - 1), candles.get(index), gaps)) {
        result++;
      }
    }
    return result;
  }

  private static boolean hasGap(ChartCandle previous, ChartCandle next, List<ChartGap> gaps) {
    return gaps.stream().anyMatch(gap -> !gap.previousBucketStart().isBefore(previous.bucketStart())
        && !gap.nextBucketStart().isAfter(next.bucketStart()));
  }

  private static void drawGapMarkers(Canvas canvas, MarketChartLayout.Rect plot,
                                     MarketChartSeries series, int[] positions) {
    for (int index = 1; index < series.candles().size(); index++) {
      if (hasGap(series.candles().get(index - 1), series.candles().get(index), series.gaps())) {
        int x = (positions[index - 1] + positions[index]) / 2;
        canvas.dashedVertical(x, plot.top() + 2, plot.bottom() - 2,
            MarketChartPalette.GAP_MARKER, 2);
      }
    }
  }

  private static void drawTrustedReference(Canvas canvas, MarketChartLayout.Rect plot,
                                           MarketChartSeries series, int[] candlePositions) {
    List<TrustedPricePoint> points = series.trustedPoints();
    if (points.size() == 1) {
      int y = priceToY(plot, series, points.getFirst().price());
      canvas.dashedHorizontal(plot.left() + 2, plot.right() - 2, y,
          MarketChartPalette.TRUSTED_REFERENCE, 2);
      return;
    }
    int previousX = trustedX(points.getFirst().at(), series, candlePositions, plot);
    int previousY = priceToY(plot, series, points.getFirst().price());
    drawPointMarker(canvas, previousX, previousY, MarketChartPalette.TRUSTED_REFERENCE);
    for (int index = 1; index < points.size(); index++) {
      TrustedPricePoint point = points.get(index);
      int x = trustedX(point.at(), series, candlePositions, plot);
      int y = priceToY(plot, series, point.price());
      canvas.line(previousX, previousY, x, y, MarketChartPalette.TRUSTED_REFERENCE);
      previousX = x;
      previousY = y;
    }
    drawPointMarker(canvas, previousX, previousY, MarketChartPalette.TRUSTED_REFERENCE);
  }

  private static int trustedX(Instant at, MarketChartSeries series, int[] positions,
                              MarketChartLayout.Rect plot) {
    List<ChartCandle> candles = series.candles();
    if (candles.isEmpty()) {
      List<TrustedPricePoint> points = series.trustedPoints();
      if (points.size() == 1) {
        return plot.left() + plot.width() / 2;
      }
      Instant first = points.getFirst().at();
      Instant last = points.getLast().at();
      return interpolateTime(at, first, last, plot.left() + 2, plot.right() - 2);
    }
    if (!at.isAfter(candles.getFirst().bucketStart())) {
      return positions[0];
    }
    if (!at.isBefore(candles.getLast().bucketStart())) {
      return positions[positions.length - 1];
    }
    for (int index = 1; index < candles.size(); index++) {
      Instant right = candles.get(index).bucketStart();
      if (!at.isAfter(right)) {
        return interpolateTime(at, candles.get(index - 1).bucketStart(), right,
            positions[index - 1], positions[index]);
      }
    }
    return positions[positions.length - 1];
  }

  private static int interpolateTime(Instant value, Instant from, Instant to,
                                     int fromX, int toX) {
    long duration = to.toEpochMilli() - from.toEpochMilli();
    if (duration <= 0) {
      return fromX;
    }
    double ratio = (double) (value.toEpochMilli() - from.toEpochMilli()) / duration;
    ratio = Math.max(0.0, Math.min(1.0, ratio));
    return fromX + (int) Math.round((toX - fromX) * ratio);
  }

  private static void drawRawLatestMarker(Canvas canvas, MarketChartLayout layout,
                                          MarketChartSeries series) {
    int rawY = priceToY(layout.plot(), series, series.latestRawPrice());
    canvas.dashedHorizontal(layout.plot().left() + 1, layout.plot().right() - 1, rawY,
        MarketChartPalette.HIGHLIGHT, 3);
    layout.priceAxis().ifPresent(axis -> {
      int labelY = labelY(axis, rawY);
      int trustedLabelY = labelY(axis,
          priceToY(layout.plot(), series, series.latestTrustedPrice()));
      if (Math.abs(labelY - trustedLabelY) < 5) {
        labelY = labelY(axis, rawY <= trustedLabelY ? rawY - 6 : rawY + 6);
      }
      PixelFont.draw(canvas::set, trimToPixels(compactPrice(series.latestRawPrice()), axis.width()),
          axis.left() + 1, labelY, MarketChartPalette.HIGHLIGHT);
    });
  }

  private static void drawPriceLabel(Canvas canvas, MarketChartLayout layout,
                                     MarketChartSeries series, BigDecimal price, byte color) {
    int y = priceToY(layout.plot(), series, price);
    layout.priceAxis().ifPresent(axis -> {
      PixelFont.draw(canvas::set, trimToPixels(compactPrice(price), axis.width()),
          axis.left() + 1, labelY(axis, y), color);
    });
  }

  private static int labelY(MarketChartLayout.Rect axis, int priceY) {
    return Math.max(axis.top(), Math.min(axis.bottom() - 4, priceY - 2));
  }

  private static void drawLegendAndConfidence(Canvas canvas, MarketChartLayout layout,
                                              MarketChartSeries series) {
    layout.legend().ifPresent(area -> {
      PixelFont.draw(canvas::set, "RAW", area.left(), area.top() + 2, MarketChartPalette.RISE);
      if (!series.trustedPoints().isEmpty()) {
        PixelFont.draw(canvas::set, "REF", area.left() + 22, area.top() + 2,
            MarketChartPalette.TRUSTED_REFERENCE);
      }
    });
    layout.confidence().ifPresent(area -> PixelFont.draw(canvas::set,
        compactText(series.liquidityTier().name(), 8), area.left(), area.top() + 2,
        MarketChartPalette.CONFIDENCE));
  }

  private static void drawPointMarker(Canvas canvas, int x, int y, byte color) {
    canvas.set(x, y, color);
    canvas.set(x - 1, y, color);
    canvas.set(x + 1, y, color);
    canvas.set(x, y - 1, color);
    canvas.set(x, y + 1, color);
    canvas.set(x - 1, y - 1, color);
    canvas.set(x + 1, y - 1, color);
    canvas.set(x - 1, y + 1, color);
    canvas.set(x + 1, y + 1, color);
  }

  private static int priceToY(MarketChartLayout.Rect plot, MarketChartSeries series,
                              BigDecimal price) {
    int top = plot.top() + 2;
    int bottom = plot.bottom() - 2;
    BigDecimal range = series.maximumPrice().subtract(series.minimumPrice());
    if (range.signum() <= 0) {
      return (top + bottom) / 2;
    }
    BigDecimal normalized = price.subtract(series.minimumPrice())
        .divide(range, 12, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE);
    return bottom - normalized.multiply(BigDecimal.valueOf(bottom - top))
        .setScale(0, RoundingMode.HALF_UP).intValue();
  }

  private static byte directionColor(BigDecimal from, BigDecimal to) {
    int direction = to.compareTo(from);
    return direction > 0 ? MarketChartPalette.RISE
        : direction < 0 ? MarketChartPalette.FALL : MarketChartPalette.FLAT;
  }

  static String compactPrice(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    String plain = stripped.toPlainString();
    if (plain.length() <= 7) {
      return plain;
    }
    int exponent = stripped.precision() - stripped.scale() - 1;
    BigDecimal mantissa = stripped.movePointLeft(exponent)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros();
    if (mantissa.abs().compareTo(BigDecimal.TEN) >= 0) {
      mantissa = mantissa.movePointLeft(1);
      exponent++;
    }
    return mantissa.toPlainString() + "E" + exponent;
  }

  private static String percent(BigDecimal change) {
    return (change.signum() > 0 ? "+" : "")
        + change.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + "%";
  }

  static String compactText(String value, int maximumCharacters) {
    return compactText(value, "MARKET", maximumCharacters);
  }

  static String compactText(String value, String fallback, int maximumCharacters) {
    String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
    if (normalized.isEmpty()) {
      normalized = fallback.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
    }
    if (normalized.isEmpty()) {
      normalized = "MARKET";
    }
    return normalized.substring(0, Math.min(maximumCharacters, normalized.length()));
  }

  private static String trimToPixels(String value, int pixels) {
    int characters = Math.max(1, (pixels + 1) / 4);
    return value.substring(0, Math.min(characters, value.length()));
  }

  private static final class Canvas {
    private final int width;
    private final int height;
    private final byte[] pixels;

    private Canvas(int width, int height) {
      this.width = width;
      this.height = height;
      this.pixels = new byte[Math.multiplyExact(width, height)];
    }

    private void fill(byte color) {
      Arrays.fill(pixels, color);
    }

    private void set(int x, int y, byte color) {
      if (x >= 0 && x < width && y >= 0 && y < height) {
        pixels[y * width + x] = color;
      }
    }

    private void rectangle(MarketChartLayout.Rect rect, byte color) {
      horizontal(rect.left(), rect.right(), rect.top(), color);
      horizontal(rect.left(), rect.right(), rect.bottom(), color);
      vertical(rect.left(), rect.top(), rect.bottom(), color);
      vertical(rect.right(), rect.top(), rect.bottom(), color);
    }

    private void horizontal(int fromX, int toX, int y, byte color) {
      for (int x = Math.min(fromX, toX); x <= Math.max(fromX, toX); x++) set(x, y, color);
    }

    private void vertical(int x, int fromY, int toY, byte color) {
      for (int y = Math.min(fromY, toY); y <= Math.max(fromY, toY); y++) set(x, y, color);
    }

    private void dashedHorizontal(int fromX, int toX, int y, byte color, int spacing) {
      for (int x = fromX; x <= toX; x++) if ((x - fromX) % spacing == 0) set(x, y, color);
    }

    private void dashedVertical(int x, int fromY, int toY, byte color, int spacing) {
      for (int y = fromY; y <= toY; y++) if ((y - fromY) % spacing == 0) set(x, y, color);
    }

    private void line(int x0, int y0, int x1, int y1, byte color) {
      int deltaX = Math.abs(x1 - x0);
      int stepX = x0 < x1 ? 1 : -1;
      int deltaY = -Math.abs(y1 - y0);
      int stepY = y0 < y1 ? 1 : -1;
      int error = deltaX + deltaY;
      while (true) {
        set(x0, y0, color);
        if (x0 == x1 && y0 == y1) return;
        int doubled = error * 2;
        if (doubled >= deltaY) { error += deltaY; x0 += stepX; }
        if (doubled <= deltaX) { error += deltaX; y0 += stepY; }
      }
    }

    private MarketChartImage image() {
      return new MarketChartImage(width, height, pixels);
    }
  }
}
