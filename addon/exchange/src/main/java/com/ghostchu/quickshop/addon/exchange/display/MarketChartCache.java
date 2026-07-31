package com.ghostchu.quickshop.addon.exchange.display;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MarketChartCache implements AutoCloseable {
  private final int capacity;
  private final Map<Key, MarketChartImage> entries;
  private boolean closed;

  public MarketChartCache(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("cache capacity must be positive");
    }
    this.capacity = capacity;
    this.entries = new LinkedHashMap<>(16, 0.75f, true);
  }

  public synchronized MarketChartImage getOrRender(Key key, Supplier<MarketChartImage> renderer) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(renderer, "renderer");
    if (closed) {
      throw new IllegalStateException("market chart cache is closed");
    }
    MarketChartImage cached = entries.get(key);
    if (cached != null) {
      return cached;
    }
    MarketChartImage rendered = Objects.requireNonNull(renderer.get(), "rendered image");
    entries.put(key, rendered);
    while (entries.size() > capacity) {
      Key eldest = entries.keySet().iterator().next();
      entries.remove(eldest);
    }
    return rendered;
  }

  public synchronized int size() {
    return entries.size();
  }

  @Override
  public synchronized void close() {
    closed = true;
    entries.clear();
  }

  public record Key(String marketId, MarketChartMode mode, MarketChartPeriod period,
                    MarketChartDimensions dimensions, String fingerprint,
                    String chartOptionsFingerprint, long trustedStateVersion,
                    MarketChartInterval selectedInterval) {
    public Key(String marketId, MarketChartMode mode, MarketChartPeriod period,
               MarketChartDimensions dimensions, String fingerprint) {
      this(marketId, mode, period, dimensions, fingerprint, "legacy", 0L,
          MarketChartInterval.FIVE_MINUTES);
    }

    public Key {
      if (marketId == null || marketId.isBlank() || fingerprint == null
          || fingerprint.isBlank()) {
        throw new IllegalArgumentException("complete chart cache identity is required");
      }
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(period, "period");
      Objects.requireNonNull(dimensions, "dimensions");
      if (chartOptionsFingerprint == null || chartOptionsFingerprint.isBlank()
          || trustedStateVersion < 0) {
        throw new IllegalArgumentException("complete chart cache version is required");
      }
      Objects.requireNonNull(selectedInterval, "selectedInterval");
    }
  }
}
