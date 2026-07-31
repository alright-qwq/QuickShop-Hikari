package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects the finest sparse interval that fits a chart's active-candle budget. */
public final class AdaptiveChartIntervalSelector {
  private static final int CANDLES_PER_FRAME = 12;

  private final SparseCandleAggregator aggregator;

  public AdaptiveChartIntervalSelector() {
    this(new SparseCandleAggregator());
  }

  AdaptiveChartIntervalSelector(SparseCandleAggregator aggregator) {
    this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
  }

  public Selection select(List<Candle> source, MarketChartDimensions dimensions) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(dimensions, "dimensions");
    int target = Math.multiplyExact(Math.multiplyExact(dimensions.columns(), dimensions.rows()),
        CANDLES_PER_FRAME);
    for (MarketChartInterval interval : MarketChartInterval.values()) {
      List<Candle> aggregated = aggregator.aggregate(source, interval);
      if (aggregated.size() <= target) {
        return new Selection(interval, aggregated, gaps(aggregated, interval));
      }
      if (interval == MarketChartInterval.ONE_DAY) {
        List<ChartGap> gaps = gaps(aggregated, interval);
        return new Selection(interval, downsample(aggregated, target, interval), gaps);
      }
    }
    throw new IllegalStateException("no chart interval available");
  }

  private static List<ChartGap> gaps(List<Candle> candles, MarketChartInterval interval) {
    List<ChartGap> gaps = new ArrayList<>();
    for (int index = 1; index < candles.size(); index++) {
      Instant previous = candles.get(index - 1).bucketStart();
      Instant next = candles.get(index).bucketStart();
      if (next.isAfter(previous.plus(interval.duration()))) {
        gaps.add(new ChartGap(previous, next));
      }
    }
    return List.copyOf(gaps);
  }

  private static List<Candle> downsample(List<Candle> aggregated, int target,
                                         MarketChartInterval interval) {
    List<List<Candle>> runs = splitRuns(aggregated, interval);
    if (runs.size() > target) {
      // Every active run needs a representative to preserve the sparse timeline and its gaps.
      return aggregated;
    }
    int[] groups = new int[runs.size()];
    int totalGroups = 0;
    for (int index = 0; index < runs.size(); index++) {
      groups[index] = 1;
      totalGroups++;
    }
    while (totalGroups < target) {
      int selected = -1;
      for (int index = 0; index < runs.size(); index++) {
        if (groups[index] >= runs.get(index).size()) {
          continue;
        }
        if (selected < 0 || runs.get(index).size() * groups[selected]
            > runs.get(selected).size() * groups[index]) {
          selected = index;
        }
      }
      if (selected < 0) {
        break;
      }
      groups[selected]++;
      totalGroups++;
    }

    List<Candle> result = new ArrayList<>(target);
    for (int index = 0; index < runs.size(); index++) {
      result.addAll(partition(runs.get(index), groups[index], interval));
    }
    return List.copyOf(result);
  }

  private static List<List<Candle>> splitRuns(List<Candle> candles, MarketChartInterval interval) {
    if (candles.isEmpty()) {
      return List.of();
    }
    List<List<Candle>> runs = new ArrayList<>();
    List<Candle> current = new ArrayList<>();
    current.add(candles.getFirst());
    for (int index = 1; index < candles.size(); index++) {
      Candle previous = candles.get(index - 1);
      Candle next = candles.get(index);
      if (next.bucketStart().isAfter(previous.bucketStart().plus(interval.duration()))) {
        runs.add(List.copyOf(current));
        current = new ArrayList<>();
      }
      current.add(next);
    }
    runs.add(List.copyOf(current));
    return List.copyOf(runs);
  }

  private static List<Candle> partition(List<Candle> run, int groups, MarketChartInterval interval) {
    if (groups >= run.size()) {
      return run;
    }
    List<Candle> result = new ArrayList<>(groups);
    for (int group = 0; group < groups; group++) {
      int start = group * run.size() / groups;
      int end = (group + 1) * run.size() / groups;
      result.add(aggregate(run.subList(start, end), interval));
    }
    return result;
  }

  private static Candle aggregate(List<Candle> candles, MarketChartInterval interval) {
    List<Candle> sorted = candles.stream()
        .sorted(Comparator.comparing(Candle::bucketStart))
        .toList();
    Candle first = sorted.getFirst();
    Candle last = sorted.getLast();
    BigDecimal high = sorted.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
    BigDecimal low = sorted.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
    long volume = sorted.stream().mapToLong(Candle::volume).reduce(0L, Math::addExact);
    BigDecimal notional = sorted.stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Candle(first.marketId(), first.bucketStart(), first.open(), high, low, last.close(),
        volume, notional);
  }

  public record Selection(MarketChartInterval interval, List<Candle> candles, List<ChartGap> gaps) {
    public Selection {
      Objects.requireNonNull(interval, "interval");
      candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
      gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
    }
  }
}
