package com.ghostchu.quickshop.addon.exchange.display;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDisplayRegistryTest {
  @TempDir
  Path directory;

  @Test
  void roundTripsMapWallsAndSigns() throws Exception {
    Path file = directory.resolve("displays.yml");
    UUID mapId = UUID.randomUUID();
    UUID signId = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    MapWallBinding wall = new MapWallBinding(mapId, "minecraft_diamond/default",
        MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY, new MarketChartDimensions(2, 1),
        List.of(
            new MapFrameBinding(UUID.randomUUID(), worldId, 10, 64, 20, 41),
            new MapFrameBinding(UUID.randomUUID(), worldId, 11, 64, 20, 42)));
    MarketSignBinding sign = new MarketSignBinding(signId, "minecraft_diamond/default",
        new DisplayLocation(worldId, 12, 64, 20), MarketSignFormat.DEFAULT);
    MarketDisplayRegistry registry = MarketDisplayRegistry.load(file);

    registry.put(wall);
    registry.put(sign);
    registry.save();

    MarketDisplayRegistry restored = MarketDisplayRegistry.load(file);
    assertThat(restored.mapWalls()).containsExactly(wall);
    assertThat(restored.signs()).containsExactly(sign);
    assertThat(restored.mapWall(mapId)).contains(wall);
    assertThat(restored.sign(signId)).contains(sign);
  }

  @Test
  void skipsMalformedBindingWithoutDiscardingValidBindings() throws Exception {
    Path file = directory.resolve("displays.yml");
    UUID validId = UUID.randomUUID();
    UUID worldId = UUID.randomUUID();
    Files.writeString(file, """
        maps:
          broken:
            market: minecraft_diamond/default
            mode: bars
            period: 24h
            dimensions: 3x3
          %s:
            market: minecraft_diamond/default
            mode: line
            period: 6h
            dimensions: 1x1
            frames:
              - entity: %s
                world: %s
                x: 1
                y: 64
                z: 2
                map-id: 7
        """.formatted(validId, UUID.randomUUID(), worldId));

    MarketDisplayRegistry restored = MarketDisplayRegistry.load(file);

    assertThat(restored.mapWalls()).singleElement().satisfies(binding -> {
      assertThat(binding.bindingId()).isEqualTo(validId);
      assertThat(binding.mode()).isEqualTo(MarketChartMode.LINE);
      assertThat(binding.period()).isEqualTo(MarketChartPeriod.SIX_HOURS);
    });
    assertThat(restored.diagnostics()).hasSize(1);
  }

  @Test
  void returnsImmutableSnapshotsAndSupportsRemoval() throws Exception {
    Path file = directory.resolve("displays.yml");
    MarketDisplayRegistry registry = MarketDisplayRegistry.load(file);
    UUID id = UUID.randomUUID();
    MarketSignBinding sign = new MarketSignBinding(id, "market",
        new DisplayLocation(UUID.randomUUID(), 1, 2, 3), MarketSignFormat.DEFAULT);
    registry.put(sign);

    assertThat(registry.removeSign(id)).contains(sign);
    assertThat(registry.signs()).isEmpty();
  }

  @Test
  void locatesManagedFramesSignsAndChunkBindingsForProtectionAndRecovery() {
    Path file = directory.resolve("displays.yml");
    MarketDisplayRegistry registry = MarketDisplayRegistry.load(file);
    UUID world = UUID.randomUUID();
    UUID frameId = UUID.randomUUID();
    MapWallBinding wall = new MapWallBinding(UUID.randomUUID(), "market",
        MarketChartMode.LINE, MarketChartPeriod.ONE_DAY, new MarketChartDimensions(1, 1),
        List.of(new MapFrameBinding(frameId, world, 33, 64, -17, 8)));
    MarketSignBinding sign = new MarketSignBinding(UUID.randomUUID(), "market",
        new DisplayLocation(world, 34, 64, -16), MarketSignFormat.DEFAULT);
    registry.put(wall);
    registry.put(sign);

    assertThat(registry.managesFrame(frameId)).isTrue();
    assertThat(registry.managesSign(sign.location())).isTrue();
    assertThat(registry.mapWallsInChunk(world, 2, -2)).containsExactly(wall);
    assertThat(registry.signsInChunk(world, 2, -1)).containsExactly(sign);
    assertThat(registry.managesFrame(UUID.randomUUID())).isFalse();
  }
}
