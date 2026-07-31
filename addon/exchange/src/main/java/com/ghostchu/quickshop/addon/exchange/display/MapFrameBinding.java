package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Objects;
import java.util.UUID;

public record MapFrameBinding(UUID entityId, UUID worldId, int x, int y, int z, int mapId) {
  public MapFrameBinding {
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(worldId, "worldId");
    if (mapId < 0) {
      throw new IllegalArgumentException("mapId must not be negative");
    }
  }

  public DisplayLocation location() {
    return new DisplayLocation(worldId, x, y, z);
  }
}
