package com.ghostchu.quickshop.addon.exchange.display;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDisplayListenerTest {
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
  void cancelsInteractionAndBreakForManagedFrame() throws Exception {
    WorldMock world = server.addSimpleWorld("display-frame-world");
    ItemFrame frame = world.spawn(new Location(world, 0, 64, 0), ItemFrame.class);
    MarketDisplayRegistry registry = registry();
    registry.put(wall(frame, 7));
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(new ArrayList<>()));
    var player = server.addPlayer();
    PlayerInteractEntityEvent interact = new PlayerInteractEntityEvent(
        player, frame, EquipmentSlot.HAND);
    HangingBreakByEntityEvent broken = new HangingBreakByEntityEvent(frame, player);

    listener.onInteract(interact);
    listener.onHangingBreak(broken);

    assertThat(interact.isCancelled()).isTrue();
    assertThat(broken.isCancelled()).isTrue();
  }

  @Test
  void registersHighestPriorityHandlerForEveryEntityDamageEvent() throws Exception {
    Method handler = MarketDisplayListener.class.getMethod("onEntityDamage", EntityDamageEvent.class);
    EventHandler annotation = handler.getAnnotation(EventHandler.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.priority()).isEqualTo(EventPriority.HIGHEST);
    assertThat(annotation.ignoreCancelled()).isTrue();
  }

  @Test
  void cancelsAllDamageToManagedFramesWithoutProtectingOrdinaryFrames() throws Exception {
    WorldMock world = server.addSimpleWorld("display-frame-damage-world");
    ItemFrame managed = world.spawn(new Location(world, 0, 64, 0), ItemFrame.class);
    ItemFrame ordinary = world.spawn(new Location(world, 1, 64, 0), ItemFrame.class);
    MarketDisplayRegistry registry = registry();
    registry.put(wall(managed, 7));
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(new ArrayList<>()));
    EntityDamageEvent environmentDamage = new EntityDamageEvent(
        managed, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, 1.0D);
    EntityDamageByEntityEvent nonPlayerDamage = new EntityDamageByEntityEvent(
        ordinary, managed, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0D);
    EntityDamageEvent ordinaryDamage = new EntityDamageEvent(
        ordinary, EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, 1.0D);

    listener.onEntityDamage(environmentDamage);
    listener.onEntityDamage(nonPlayerDamage);
    listener.onEntityDamage(ordinaryDamage);

    assertThat(environmentDamage.isCancelled()).isTrue();
    assertThat(nonPlayerDamage.isCancelled()).isTrue();
    assertThat(ordinaryDamage.isCancelled()).isFalse();
  }

  @Test
  void removesManagedSignsFromBlockExplosionWithoutCancellingOtherDamage() throws Exception {
    WorldMock world = server.addSimpleWorld("display-block-explosion-world");
    Block managed = world.getBlockAt(1, 64, 1);
    Block ordinary = world.getBlockAt(2, 64, 1);
    managed.setType(Material.OAK_SIGN);
    ordinary.setType(Material.STONE);
    MarketDisplayRegistry registry = registry();
    registry.put(sign(managed));
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(new ArrayList<>()));
    List<Block> affected = new ArrayList<>(List.of(managed, ordinary));
    BlockExplodeEvent event = new BlockExplodeEvent(
        world.getBlockAt(0, 64, 0), managed.getState(), affected, 1.0f,
        ExplosionResult.DESTROY);

    listener.onBlockExplode(event);

    assertThat(event.isCancelled()).isFalse();
    assertThat(event.blockList()).containsExactly(ordinary);
  }

  @Test
  void cancelsPistonsThatMoveManagedSignsOrPushIntoThem() throws Exception {
    WorldMock world = server.addSimpleWorld("display-piston-world");
    Block piston = world.getBlockAt(0, 64, 0);
    Block managed = world.getBlockAt(1, 64, 0);
    Block beforeManaged = world.getBlockAt(0, 64, 0);
    managed.setType(Material.OAK_SIGN);
    MarketDisplayRegistry registry = registry();
    registry.put(sign(managed));
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(new ArrayList<>()));
    BlockPistonExtendEvent extend = new BlockPistonExtendEvent(
        piston, List.of(beforeManaged), BlockFace.EAST);
    BlockPistonRetractEvent retract = new BlockPistonRetractEvent(
        piston, List.of(managed), BlockFace.WEST);

    listener.onPistonExtend(extend);
    listener.onPistonRetract(retract);

    assertThat(extend.isCancelled()).isTrue();
    assertThat(retract.isCancelled()).isTrue();
  }

  @Test
  void refreshesOnlyBindingsInLoadedChunk() throws Exception {
    WorldMock world = server.addSimpleWorld("display-chunk-world");
    ItemFrame insideFrame = world.spawn(new Location(world, 1, 64, 1), ItemFrame.class);
    ItemFrame outsideFrame = world.spawn(new Location(world, 33, 64, 1), ItemFrame.class);
    Block insideSign = world.getBlockAt(2, 64, 1);
    Block outsideSign = world.getBlockAt(34, 64, 1);
    insideSign.setType(Material.OAK_SIGN);
    outsideSign.setType(Material.OAK_SIGN);
    MarketDisplayRegistry registry = registry();
    registry.put(wall(insideFrame, 7));
    registry.put(wall(outsideFrame, 8));
    registry.put(sign(insideSign));
    registry.put(sign(outsideSign));
    List<String> updates = new ArrayList<>();
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(updates));

    listener.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));

    assertThat(updates).containsExactly("diamond-usd", "diamond-usd");
  }

  @Test
  void removesManagedSignsFromEntityExplosionWithoutCancellingOtherDamage() throws Exception {
    WorldMock world = server.addSimpleWorld("display-entity-explosion-world");
    Block managed = world.getBlockAt(1, 64, 1);
    Block ordinary = world.getBlockAt(2, 64, 1);
    managed.setType(Material.OAK_SIGN);
    ordinary.setType(Material.STONE);
    MarketDisplayRegistry registry = registry();
    registry.put(sign(managed));
    MarketDisplayListener listener = new MarketDisplayListener(registry, service(new ArrayList<>()));
    ItemFrame source = world.spawn(new Location(world, 0, 64, 0), ItemFrame.class);
    List<Block> affected = new ArrayList<>(List.of(managed, ordinary));
    EntityExplodeEvent event = new EntityExplodeEvent(
        source, source.getLocation(), affected, 1.0f, ExplosionResult.DESTROY);

    listener.onEntityExplode(event);

    assertThat(event.isCancelled()).isFalse();
    assertThat(event.blockList()).containsExactly(ordinary);
  }

  private static MarketDisplayRegistry registry() throws Exception {
    return MarketDisplayRegistry.load(
        java.nio.file.Files.createTempDirectory("display-listener-").resolve("displays.yml"));
  }

  private static MapWallBinding wall(ItemFrame frame, int mapId) {
    Location location = frame.getLocation();
    return new MapWallBinding(UUID.randomUUID(), "diamond-usd", MarketChartMode.KLINE,
        MarketChartPeriod.ONE_DAY, new MarketChartDimensions(1, 1), List.of(
            new MapFrameBinding(frame.getUniqueId(), frame.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), mapId)));
  }

  private static MarketSignBinding sign(Block block) {
    return new MarketSignBinding(UUID.randomUUID(), "diamond-usd",
        new DisplayLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()),
        MarketSignFormat.DEFAULT);
  }

  private static MarketDisplayService service(List<String> updates) {
    DisplayScheduler scheduler = new DisplayScheduler() {
      @Override
      public CompletableFuture<Void> updateMapFrame(MapFrameBinding frame, MarketChartImage image) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletableFuture<Void> updateSign(MarketSignBinding sign, MarketSignLines lines) {
        return CompletableFuture.completedFuture(null);
      }
    };
    return new MarketDisplayService((marketId, period, toExclusive) -> {
      updates.add(marketId);
      return CompletableFuture.failedFuture(new AssertionError("snapshot not expected"));
    }, new MarketChartSeriesBuilder(), new MarketChartRenderer(), new MarketChartCache(1),
        new MarketSignFormatter(), scheduler, Clock.systemUTC());
  }
}
