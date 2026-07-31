package com.ghostchu.quickshop.addon.exchange.display;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record MapWallBinding(UUID bindingId, String marketId, MarketChartMode mode,
                             MarketChartPeriod period, MarketChartDimensions dimensions,
                             List<MapFrameBinding> frames) {
  public MapWallBinding {
    Objects.requireNonNull(bindingId, "bindingId");
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(dimensions, "dimensions");
    frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
    if (frames.size() != dimensions.columns() * dimensions.rows()) {
      throw new IllegalArgumentException("frame count does not match wall dimensions");
    }
  }
}
