package com.ghostchu.quickshop.addon.exchange.display;

import java.time.Instant;
import java.util.Objects;

/** A missing span between two active chart buckets. */
public record ChartGap(Instant previousBucketStart, Instant nextBucketStart) {
  public ChartGap {
    Objects.requireNonNull(previousBucketStart, "previousBucketStart");
    Objects.requireNonNull(nextBucketStart, "nextBucketStart");
    if (!previousBucketStart.isBefore(nextBucketStart)) {
      throw new IllegalArgumentException("chart gap must move forward in time");
    }
  }

  public Instant fromBucketStart() {
    return previousBucketStart;
  }

  public Instant toBucketStart() {
    return nextBucketStart;
  }
}
