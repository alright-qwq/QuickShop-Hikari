package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-memory UTC-minute OHLCV aggregation; persistence is performed by the caller at rollover. */
public final class CandleAggregator {
  private final Map<Key, Candle> candles = new HashMap<>();

  public synchronized void record(String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    if (marketId == null || marketId.isBlank() || price == null || price.signum() <= 0
        || quantity <= 0 || occurredAt == null) {
      throw new IllegalArgumentException("invalid candle trade");
    }
    Instant bucket = Instant.ofEpochSecond(Math.floorDiv(occurredAt.getEpochSecond(), 60) * 60L);
    Key key = new Key(marketId, bucket);
    Candle previous = candles.get(key);
    BigDecimal notional = price.multiply(BigDecimal.valueOf(quantity));
    Candle next = previous == null
        ? new Candle(marketId, bucket, price, price, price, price, quantity, notional)
        : new Candle(marketId, bucket, previous.open(), previous.high().max(price),
            previous.low().min(price), price, Math.addExact(previous.volume(), quantity),
            previous.notional().add(notional));
    candles.put(key, next);
  }

  public synchronized Optional<Candle> snapshot(String marketId, Instant bucketStart) {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(bucketStart, "bucketStart");
    Instant bucket = Instant.ofEpochSecond(Math.floorDiv(bucketStart.getEpochSecond(), 60) * 60L);
    return Optional.ofNullable(candles.get(new Key(marketId, bucket)));
  }

  private record Key(String marketId, Instant bucketStart) {}
}
