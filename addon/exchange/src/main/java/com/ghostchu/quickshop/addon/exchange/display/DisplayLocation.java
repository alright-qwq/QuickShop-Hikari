package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Objects;
import java.util.UUID;

public record DisplayLocation(UUID worldId, int x, int y, int z) {
  public DisplayLocation {
    Objects.requireNonNull(worldId, "worldId");
  }
}
