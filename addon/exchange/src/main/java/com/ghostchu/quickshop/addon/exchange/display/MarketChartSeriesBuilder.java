package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class MarketChartSeriesBuilder {
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
}
