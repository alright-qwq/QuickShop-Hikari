package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record ChartCandle(Instant bucketStart, BigDecimal open, BigDecimal high,
                          BigDecimal low, BigDecimal close, long volume,
                          BigDecimal notional) {
  public ChartCandle {
    Objects.requireNonNull(bucketStart, "bucketStart");
    Objects.requireNonNull(open, "open");
    Objects.requireNonNull(high, "high");
    Objects.requireNonNull(low, "low");
    Objects.requireNonNull(close, "close");
    Objects.requireNonNull(notional, "notional");
    if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
        || low.compareTo(open) > 0 || low.compareTo(close) > 0 || volume < 0
        || notional.signum() < 0) {
      throw new IllegalArgumentException("invalid chart candle");
    }
  }
}
