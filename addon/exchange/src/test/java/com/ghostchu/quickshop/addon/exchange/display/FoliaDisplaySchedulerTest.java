package com.ghostchu.quickshop.addon.exchange.display;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FoliaDisplaySchedulerTest {
  private static ServerMock server;

  @BeforeAll
  static void startMockServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopMockServer() {
    MockBukkit.unmock();
  }

  @Test
  void resolvesFrameOnlyAfterEnteringItsRegionThenUsesEntityScheduler() throws Exception {
    WorldMock world = server.addSimpleWorld("folia-frame-world");
    ItemFrame frame = world.spawn(new Location(world, 1, 64, 1), ItemFrame.class);
    List<String> calls = new ArrayList<>();
    FoliaDisplayScheduler scheduler = new FoliaDisplayScheduler(new Access() {
      @Override public World world(UUID worldId) { calls.add("world"); return world; }
      @Override public void runAtLocation(Location location, Runnable action) {
        calls.add("location"); action.run();
      }
      @Override public boolean chunkLoaded(World owner, int chunkX, int chunkZ) {
        calls.add("chunk"); return true;
      }
      @Override public Entity entity(World owner, UUID entityId) {
        calls.add("entity"); return frame;
      }
      @Override public void runAtEntity(ItemFrame owner, Runnable action) {
        calls.add("entity-scheduler"); action.run();
      }
      @Override public MapView map(int mapId) { calls.add("map"); return null; }
    });

    scheduler.updateMapFrame(new MapFrameBinding(frame.getUniqueId(), world.getUID(),
        1, 64, 1, 7), image()).get(5, TimeUnit.SECONDS);

    assertThat(calls).containsExactly(
        "world", "location", "chunk", "entity", "entity-scheduler", "map");
  }

  @Test
  void skipsFrameWithoutSchedulingARegionWhenWorldIsMissing() throws Exception {
    List<String> calls = new ArrayList<>();
    FoliaDisplayScheduler scheduler = new FoliaDisplayScheduler(new Access() {
      @Override public World world(UUID worldId) { calls.add("world"); return null; }
      @Override public void runAtLocation(Location location, Runnable action) {
        throw new AssertionError("region must not be scheduled");
      }
      @Override public boolean chunkLoaded(World owner, int chunkX, int chunkZ) { return false; }
      @Override public Entity entity(World owner, UUID entityId) { return null; }
      @Override public void runAtEntity(ItemFrame owner, Runnable action) {}
      @Override public MapView map(int mapId) { return null; }
    });

    scheduler.updateMapFrame(new MapFrameBinding(UUID.randomUUID(), UUID.randomUUID(),
        1, 64, 1, 7), image()).get(5, TimeUnit.SECONDS);

    assertThat(calls).containsExactly("world");
  }

  @Test
  void checksSignChunkInsideItsRegion() throws Exception {
    WorldMock world = server.addSimpleWorld("folia-sign-world");
    List<String> calls = new ArrayList<>();
    FoliaDisplayScheduler scheduler = new FoliaDisplayScheduler(new Access() {
      @Override public World world(UUID worldId) { calls.add("world"); return world; }
      @Override public void runAtLocation(Location location, Runnable action) {
        calls.add("location"); action.run();
      }
      @Override public boolean chunkLoaded(World owner, int chunkX, int chunkZ) {
        calls.add("chunk"); return false;
      }
      @Override public Entity entity(World owner, UUID entityId) { return null; }
      @Override public void runAtEntity(ItemFrame owner, Runnable action) {}
      @Override public MapView map(int mapId) { return null; }
    });
    MarketSignBinding sign = new MarketSignBinding(UUID.randomUUID(), "diamond-usd",
        new DisplayLocation(world.getUID(), 1, 64, 1), MarketSignFormat.DEFAULT);

    scheduler.updateSign(sign, new MarketSignLines(List.of(
        new MarketSignLine("market", MarketSignTone.NORMAL),
        new MarketSignLine("price", MarketSignTone.NORMAL),
        new MarketSignLine("change", MarketSignTone.FLAT),
        new MarketSignLine("spread", MarketSignTone.NORMAL)))).get(5, TimeUnit.SECONDS);

    assertThat(calls).containsExactly("world", "location", "chunk");
  }

  private static MarketChartImage image() {
    return new MarketChartImage(128, 128, new byte[128 * 128]);
  }

  private interface Access extends FoliaDisplayScheduler.WorldAccess {}
}
