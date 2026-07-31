package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Pure Java professional market chart renderer for vanilla map pixels. */
public final class MarketChartRenderer {
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
      .withZone(ZoneOffset.UTC);
  private final MarketChartOptions options;

  public MarketChartRenderer() {
    this(MarketChartOptions.defaults());
  }

  public MarketChartRenderer(MarketChartOptions options) {
    this.options = Objects.requireNonNull(options, "options");
  }

  public MarketChartImage render(MarketChartSeries series, MarketChartMode mode,
                                 MarketChartDimensions dimensions) {
    BigDecimal latest = series.hasData() ? series.candles().getLast().close() : BigDecimal.ZERO;
    return render(series, mode, dimensions, "MARKET", MarketChartPeriod.ONE_DAY,
        latest, BigDecimal.ZERO);
  }

  public MarketChartImage render(MarketChartSeries series, MarketChartMode mode,
                                 MarketChartDimensions dimensions, String displayName,
                                 MarketChartPeriod period, BigDecimal latestPrice,
                                 BigDecimal change) {
    Objects.requireNonNull(series, "series");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(dimensions, "dimensions");
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(latestPrice, "latestPrice");
    Objects.requireNonNull(change, "change");
    Canvas canvas = new Canvas(dimensions.pixelWidth(), dimensions.pixelHeight());
    canvas.fill(MarketChartPalette.BACKGROUND);
    MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);
    if (options.professionalLayout()) {
      drawHeader(canvas, layout, displayName, mode, period, latestPrice, change);
    }
    drawFrameAndGrid(canvas, layout);
    if (!series.hasData()) {
      drawNoData(canvas, layout.plot());
      return canvas.image();
    }
    if (options.showLatestPriceLine()) {
      drawLatestPrice(canvas, layout, series, latestPrice);
    }
    if (mode == MarketChartMode.KLINE) {
      drawCandles(canvas, layout.plot(), series);
    } else {
      drawLine(canvas, layout.plot(), series);
    }
    if (options.professionalLayout()) {
      drawPriceAxis(canvas, layout, series);
      drawTimeAxis(canvas, layout.timeAxis(), series.candles());
    }
    if (options.showVolume()) {
      drawVolume(canvas, layout.volume(), series.candles());
    }
    return canvas.image();
  }

  private static void drawHeader(Canvas canvas, MarketChartLayout layout, String displayName,
                                 MarketChartMode mode, MarketChartPeriod period,
                                 BigDecimal latestPrice, BigDecimal change) {
    String market = compactText(displayName, layout.density() == MarketChartLayout.Density.COMPACT
        ? 8 : 16);
    PixelFont.draw(canvas::set, market, layout.header().left(), layout.header().top(),
        MarketChartPalette.AXIS_TEXT);
    String modeAndPeriod = (mode == MarketChartMode.KLINE ? "K" : "LINE") + " " + period.token();
    int rightX = layout.header().right() - PixelFont.width(modeAndPeriod) + 1;
    PixelFont.draw(canvas::set, modeAndPeriod, Math.max(layout.header().left(), rightX),
        layout.header().top(), MarketChartPalette.AXIS_TEXT);
    if (layout.density() == MarketChartLayout.Density.FULL) {
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
                                  MarketChartSeries series) {
    List<ChartCandle> candles = series.candles();
    int plotLeft = plot.left() + 2;
    int plotRight = plot.right() - 2;
    double step = (double) (plotRight - plotLeft + 1) / candles.size();
    int bodyHalfWidth = Math.max(1, Math.min(3, (int) Math.floor(step / 3.0)));
    for (int index = 0; index < candles.size(); index++) {
      ChartCandle candle = candles.get(index);
      int x = Math.min(plotRight, plotLeft + (int) Math.floor((index + 0.5) * step));
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
  }

  private static void drawLine(Canvas canvas, MarketChartLayout.Rect plot,
                               MarketChartSeries series) {
    List<ChartCandle> candles = series.candles();
    int plotLeft = plot.left() + 2;
    int plotRight = plot.right() - 2;
    if (candles.size() == 1) {
      int x = (plotLeft + plotRight) / 2;
      int y = priceToY(plot, series, candles.getFirst().close());
      canvas.set(x, y, MarketChartPalette.HIGHLIGHT);
      canvas.set(x - 1, y, MarketChartPalette.HIGHLIGHT);
      canvas.set(x + 1, y, MarketChartPalette.HIGHLIGHT);
      canvas.set(x, y - 1, MarketChartPalette.HIGHLIGHT);
      canvas.set(x, y + 1, MarketChartPalette.HIGHLIGHT);
      return;
    }
    int previousX = plotLeft;
    int previousY = priceToY(plot, series, candles.getFirst().close());
    for (int index = 1; index < candles.size(); index++) {
      int x = plotLeft + index * (plotRight - plotLeft) / (candles.size() - 1);
      int y = priceToY(plot, series, candles.get(index).close());
      byte color = directionColor(candles.get(index - 1).close(), candles.get(index).close());
      canvas.line(previousX, previousY, x, y, color);
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
    String label = compactPrice(latestPrice);
    int labelY = Math.max(layout.priceAxis().top(),
        Math.min(layout.priceAxis().bottom() - 4, y - 2));
    PixelFont.draw(canvas::set, trimToPixels(label, layout.priceAxis().width()),
        layout.priceAxis().left() + 1, labelY, MarketChartPalette.LATEST_PRICE);
  }

  private static void drawPriceAxis(Canvas canvas, MarketChartLayout layout,
                                    MarketChartSeries series) {
    BigDecimal range = series.maximumPrice().subtract(series.minimumPrice());
    for (int fraction = 0; fraction <= 4; fraction += 2) {
      BigDecimal price = series.maximumPrice().subtract(range.multiply(
          BigDecimal.valueOf(fraction)).divide(BigDecimal.valueOf(4), 8, RoundingMode.HALF_UP));
      int y = layout.plot().top() + fraction * (layout.plot().height() - 1) / 4;
      String label = trimToPixels(compactPrice(price), layout.priceAxis().width());
      PixelFont.draw(canvas::set, label, layout.priceAxis().left() + 1,
          Math.min(layout.priceAxis().bottom() - 4, y), MarketChartPalette.AXIS_TEXT);
    }
  }

  private static void drawVolume(Canvas canvas, MarketChartLayout.Rect area,
                                 List<ChartCandle> candles) {
    canvas.rectangle(area, MarketChartPalette.BORDER);
    long maximum = candles.stream().mapToLong(ChartCandle::volume).max().orElse(0L);
    if (maximum <= 0) {
      return;
    }
    double step = (double) Math.max(1, area.width() - 4) / candles.size();
    int barHalfWidth = Math.max(1, Math.min(3, (int) Math.floor(step / 3.0)));
    for (int index = 0; index < candles.size(); index++) {
      ChartCandle candle = candles.get(index);
      int x = Math.min(area.right() - 2,
          area.left() + 2 + (int) Math.floor((index + 0.5) * step));
      int barHeight = Math.max(1, (int) Math.round(
          (double) candle.volume() / maximum * Math.max(1, area.height() - 3)));
      byte color = candle.close().compareTo(candle.open()) >= 0
          ? MarketChartPalette.VOLUME_RISE : MarketChartPalette.VOLUME_FALL;
      for (int y = area.bottom() - 1; y >= area.bottom() - barHeight; y--) {
        canvas.horizontal(x - barHalfWidth, x + barHalfWidth, y, color);
      }
    }
  }

  private static void drawTimeAxis(Canvas canvas, MarketChartLayout.Rect area,
                                   List<ChartCandle> candles) {
    ChartCandle first = candles.getFirst();
    ChartCandle last = candles.getLast();
    String left = TIME.format(first.bucketStart());
    String right = TIME.format(last.bucketStart());
    PixelFont.draw(canvas::set, left, area.left(), area.top() + 2, MarketChartPalette.AXIS_TEXT);
    PixelFont.draw(canvas::set, right,
        Math.max(area.left(), area.right() - PixelFont.width(right) + 1), area.top() + 2,
        MarketChartPalette.AXIS_TEXT);
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
    String normalized = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
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
