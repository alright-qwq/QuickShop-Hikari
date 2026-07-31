package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

public final class MarketChartSeriesBuilder {
  private final AdaptiveChartIntervalSelector intervalSelector;
  private final MarketChartInterval fixedInterval;

  public MarketChartSeriesBuilder() {
    this(new AdaptiveChartIntervalSelector(), null);
  }

  public MarketChartSeriesBuilder(MarketChartInterval fixedInterval) {
    this(new AdaptiveChartIntervalSelector(), fixedInterval);
  }

  MarketChartSeriesBuilder(AdaptiveChartIntervalSelector intervalSelector) {
    this(intervalSelector, null);
  }

  private MarketChartSeriesBuilder(AdaptiveChartIntervalSelector intervalSelector,
                                   MarketChartInterval fixedInterval) {
    this.intervalSelector = Objects.requireNonNull(intervalSelector, "intervalSelector");
    this.fixedInterval = fixedInterval;
  }

  public MarketChartSeries build(List<Candle> rawCandles, List<TrustedPricePoint> trustedPoints,
                                 MarketChartDimensions dimensions, MarketChartPeriod period,
                                 LiquidityTier liquidityTier) {
    Objects.requireNonNull(rawCandles, "rawCandles");
    Objects.requireNonNull(trustedPoints, "trustedPoints");
    Objects.requireNonNull(dimensions, "dimensions");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(liquidityTier, "liquidityTier");

    List<Candle> deduplicated = deduplicateRawMinutes(rawCandles);
    AdaptiveChartIntervalSelector.Selection selected = fixedInterval == null
        ? intervalSelector.select(deduplicated, dimensions)
        : intervalSelector.select(deduplicated, dimensions, fixedInterval);
    List<ChartCandle> candles = selected.candles().stream()
        .map(MarketChartSeriesBuilder::toChartCandle)
        .toList();
    List<TrustedPricePoint> references = sortAndDeduplicateTrustedPoints(trustedPoints);
    if (candles.isEmpty() && references.isEmpty()) {
      return new MarketChartSeries(selected.interval(), List.of(), List.of(), selected.gaps(),
          BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, liquidityTier,
          false, false);
    }

    List<BigDecimal> lowerBounds = new ArrayList<>();
    List<BigDecimal> upperBounds = new ArrayList<>();
    candles.forEach(candle -> {
      lowerBounds.add(candle.low());
      upperBounds.add(candle.high());
    });
    references.forEach(point -> {
      lowerBounds.add(point.price());
      upperBounds.add(point.price());
    });
    BigDecimal minimum = lowerBounds.stream().min(BigDecimal::compareTo).orElseThrow();
    BigDecimal maximum = upperBounds.stream().max(BigDecimal::compareTo).orElseThrow();
    boolean flat = minimum.compareTo(maximum) == 0;
    if (flat) {
      BigDecimal padding = flatPadding(minimum);
      minimum = minimum.subtract(padding);
      maximum = maximum.add(padding);
    }
    BigDecimal latestRaw = candles.isEmpty() ? BigDecimal.ZERO : candles.getLast().close();
    BigDecimal latestTrusted = references.isEmpty() ? BigDecimal.ZERO : references.getLast().price();
    return new MarketChartSeries(selected.interval(), candles, references, selected.gaps(), minimum,
        maximum, latestRaw, latestTrusted, liquidityTier, flat, candles.size() == 1);
  }

  public MarketChartSeries build(List<Candle> source, int maxPoints) {
    Objects.requireNonNull(source, "source");
    if (maxPoints <= 0) {
      throw new IllegalArgumentException("maxPoints must be positive");
    }
    if (source.isEmpty()) {
      return MarketChartSeries.empty();
    }
    List<Candle> candles = source.stream()
        .map(candle -> Objects.requireNonNull(candle, "candle"))
        .sorted(Comparator.comparing(Candle::bucketStart))
        .toList();
    int bucketSize = Math.max(1, (int) Math.ceil((double) candles.size() / maxPoints));
    List<ChartCandle> aggregated = new ArrayList<>((candles.size() + bucketSize - 1) / bucketSize);
    for (int start = 0; start < candles.size(); start += bucketSize) {
      int end = Math.min(candles.size(), start + bucketSize);
      aggregated.add(aggregate(candles.subList(start, end)));
    }
    BigDecimal minimum = aggregated.stream()
        .map(ChartCandle::low)
        .min(BigDecimal::compareTo)
        .orElseThrow();
    BigDecimal maximum = aggregated.stream()
        .map(ChartCandle::high)
        .max(BigDecimal::compareTo)
        .orElseThrow();
    if (minimum.compareTo(maximum) == 0) {
      BigDecimal padding = minimum.abs().multiply(new BigDecimal("0.01"), MathContext.DECIMAL64);
      if (padding.signum() == 0) {
        padding = BigDecimal.ONE;
      }
      minimum = minimum.subtract(padding);
      maximum = maximum.add(padding);
    }
    return new MarketChartSeries(aggregated, minimum, maximum);
  }

  private static ChartCandle aggregate(List<Candle> candles) {
    Candle first = candles.getFirst();
    Candle last = candles.getLast();
    BigDecimal high = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
    BigDecimal low = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
    long volume = candles.stream().mapToLong(Candle::volume).reduce(0L, Math::addExact);
    BigDecimal notional = candles.stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new ChartCandle(first.bucketStart(), first.open(), high, low, last.close(),
        volume, notional.setScale(Math.max(0, notional.scale()), RoundingMode.UNNECESSARY));
  }

  private static List<Candle> deduplicateRawMinutes(List<Candle> source) {
    TreeMap<Instant, Candle> byMinute = new TreeMap<>();
    for (Candle candle : source) {
      Objects.requireNonNull(candle, "candle");
      Instant minute = Instant.ofEpochMilli(
          Math.floorDiv(candle.bucketStart().toEpochMilli(), 60_000L) * 60_000L);
      byMinute.put(minute, candle);
    }
    return List.copyOf(byMinute.values());
  }

  private static List<TrustedPricePoint> sortAndDeduplicateTrustedPoints(
      List<TrustedPricePoint> source) {
    TreeMap<Instant, TrustedPricePoint> byTime = new TreeMap<>();
    for (TrustedPricePoint point : source) {
      Objects.requireNonNull(point, "trustedPricePoint");
      byTime.put(point.at(), point);
    }
    return List.copyOf(byTime.values());
  }

  private static ChartCandle toChartCandle(Candle candle) {
    return new ChartCandle(candle.bucketStart(), candle.open(), candle.high(), candle.low(),
        candle.close(), candle.volume(), candle.notional());
  }

  private static BigDecimal flatPadding(BigDecimal price) {
    BigDecimal percent = price.abs().multiply(new BigDecimal("0.01"), MathContext.DECIMAL64);
    BigDecimal inferredTick = BigDecimal.ONE.scaleByPowerOfTen(-Math.max(0, price.scale()));
    return percent.max(inferredTick);
  }
}
