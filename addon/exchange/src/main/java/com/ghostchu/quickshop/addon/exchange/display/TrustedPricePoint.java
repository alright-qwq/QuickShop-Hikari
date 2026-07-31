package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** A trusted reference-price sample, including maintenance and administrative changes. */
public record TrustedPricePoint(Instant at, BigDecimal price) {
  public TrustedPricePoint {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(price, "price");
    if (price.signum() <= 0) {
      throw new IllegalArgumentException("trusted price must be positive");
    }
  }
}
