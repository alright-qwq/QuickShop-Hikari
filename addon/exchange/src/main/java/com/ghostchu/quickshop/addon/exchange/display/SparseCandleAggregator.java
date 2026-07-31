package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Aggregates trade-backed candles without creating buckets for inactive intervals. */
public final class SparseCandleAggregator {
  public List<Candle> aggregate(List<Candle> source, MarketChartInterval interval) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(interval, "interval");

    List<Candle> sorted = source.stream()
        .map(candle -> Objects.requireNonNull(candle, "candle"))
        .sorted(Comparator.comparing(Candle::bucketStart))
        .toList();
    rejectDuplicateMinutes(sorted);

    Map<Instant, List<Candle>> candlesByBucket = new TreeMap<>();
    for (Candle candle : sorted) {
      Instant bucket = bucketStart(candle.bucketStart(), interval);
      candlesByBucket.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(candle);
    }
    return candlesByBucket.entrySet().stream()
        .map(entry -> aggregate(entry.getKey(), entry.getValue()))
        .toList();
  }

  private static void rejectDuplicateMinutes(List<Candle> candles) {
    Instant previous = null;
    for (Candle candle : candles) {
      Instant minute = Instant.ofEpochMilli(Math.floorDiv(candle.bucketStart().toEpochMilli(), 60_000L)
          * 60_000L);
      if (minute.equals(previous)) {
        throw new IllegalArgumentException("duplicate source candle minute");
      }
      previous = minute;
    }
  }

  private static Instant bucketStart(Instant timestamp, MarketChartInterval interval) {
    long bucketMillis = interval.duration().toMillis();
    return Instant.ofEpochMilli(Math.floorDiv(timestamp.toEpochMilli(), bucketMillis) * bucketMillis);
  }

  private static Candle aggregate(Instant bucket, List<Candle> candles) {
    Candle first = candles.getFirst();
    Candle last = candles.getLast();
    BigDecimal high = candles.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
    BigDecimal low = candles.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
    long volume = candles.stream().mapToLong(Candle::volume).reduce(0L, Math::addExact);
    BigDecimal notional = candles.stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Candle(first.marketId(), bucket, first.open(), high, low, last.close(), volume, notional);
  }
}
