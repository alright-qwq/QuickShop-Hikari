package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

public final class ReferencePriceTracker {
  private final BigDecimal basePrice;
  private final long discoveryQuantity;
  private final Duration window;
  private final int scale;
  private final ArrayDeque<PriceSample> samples = new ArrayDeque<>();
  private long cumulativeDiscoveryQuantity;

  public ReferencePriceTracker(BigDecimal basePrice, long discoveryQuantity,
                               Duration window, int scale) {
    if (discoveryQuantity < 10) {
      throw new IllegalArgumentException("discovery quantity must be at least 10");
    }
    this.basePrice = basePrice;
    this.discoveryQuantity = discoveryQuantity;
    this.window = window;
    this.scale = scale;
  }

  public void record(BigDecimal price, long quantity, Instant occurredAt) {
    samples.addLast(new PriceSample(price, quantity, occurredAt));
    cumulativeDiscoveryQuantity = Math.addExact(cumulativeDiscoveryQuantity, quantity);
  }

  public BigDecimal referenceAt(Instant now) {
    Instant cutoff = now.minus(window);
    while (!samples.isEmpty() && samples.peekFirst().occurredAt().isBefore(cutoff)) {
      samples.removeFirst();
    }
    if (samples.isEmpty()) {
      return basePrice;
    }
    BigDecimal notional = BigDecimal.ZERO;
    long volume = 0;
    for (PriceSample sample : samples) {
      notional = notional.add(sample.price().multiply(BigDecimal.valueOf(sample.quantity())));
      volume = Math.addExact(volume, sample.quantity());
    }
    BigDecimal vwap = notional.divide(BigDecimal.valueOf(volume), scale + 6, RoundingMode.HALF_UP);
    BigDecimal ratio = BigDecimal.valueOf(Math.min(cumulativeDiscoveryQuantity, discoveryQuantity))
        .divide(BigDecimal.valueOf(discoveryQuantity), scale + 6, RoundingMode.HALF_UP);
    return basePrice.multiply(BigDecimal.ONE.subtract(ratio)).add(vwap.multiply(ratio))
        .setScale(scale, RoundingMode.HALF_UP);
  }

  public ReferencePriceTracker copy() {
    ReferencePriceTracker copy = new ReferencePriceTracker(
        basePrice, discoveryQuantity, window, scale);
    copy.samples.addAll(samples);
    copy.cumulativeDiscoveryQuantity = cumulativeDiscoveryQuantity;
    return copy;
  }

  public long discoveryQuantity() {
    return cumulativeDiscoveryQuantity;
  }

  public static ReferencePriceTracker restored(
      BigDecimal referencePrice, long discoveryQuantity, Duration window, int scale) {
    return new ReferencePriceTracker(referencePrice, discoveryQuantity, window, scale);
  }
}
