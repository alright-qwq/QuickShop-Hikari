package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Objects;
import java.util.UUID;

public record MarketSignBinding(UUID bindingId, String marketId, DisplayLocation location,
                                MarketSignFormat format) {
  public MarketSignBinding {
    Objects.requireNonNull(bindingId, "bindingId");
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(format, "format");
  }
}
