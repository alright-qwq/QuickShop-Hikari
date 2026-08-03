package com.ghostchu.quickshop.addon.exchange.display;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BukkitDisplayTargetsTest {
  private ServerMock server;

  @BeforeEach
  void startMockServer() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void stopMockServer() {
    MockBukkit.unmock();
  }

  @Test
  void ordersFramesByViewerLeftToRightForEverySupportingWallDirection() {
    assertLayout(BlockFace.SOUTH,
        frame(1, 64, 0), frame(0, 64, 0), frame(1, 63, 0), frame(0, 63, 0));
    assertLayout(BlockFace.NORTH,
        frame(0, 64, 0), frame(1, 64, 0), frame(0, 63, 0), frame(1, 63, 0));
    assertLayout(BlockFace.EAST,
        frame(0, 64, -1), frame(0, 64, 0), frame(0, 63, -1), frame(0, 63, 0));
    assertLayout(BlockFace.WEST,
        frame(0, 64, 0), frame(0, 64, -1), frame(0, 63, 0), frame(0, 63, -1));
  }

  @Test
  void rayTraceIncludesPassableSignBlocks() {
    WorldMock backingWorld = server.addSimpleWorld("sign-target-world");
    Block signBlock = signBlock(backingWorld, 1, 65, 0);
    AtomicBoolean ignoredPassableBlocks = new AtomicBoolean(true);
    World tracedWorld = proxy(World.class, (method, arguments) -> {
      if (method.getName().equals("rayTraceBlocks") && arguments != null
          && arguments.length == 5) {
        ignoredPassableBlocks.set((boolean) arguments[4]);
        return (boolean) arguments[4]
            ? null
            : new RayTraceResult(new Vector(1, 65, 0), signBlock, BlockFace.WEST);
      }
      if (method.getName().equals("getUID")) return backingWorld.getUID();
      return defaultValue(method.getReturnType());
    });
    Player player = proxy(Player.class, (method, arguments) -> switch (method.getName()) {
      case "getWorld" -> tracedWorld;
      case "getEyeLocation" -> new Location(tracedWorld, 0, 65, 0, -90.0F, 0.0F);
      case "getUniqueId" -> UUID.randomUUID();
      case "hasPermission" -> true;
      default -> defaultValue(method.getReturnType());
    });
    BukkitCommandActor actor = new BukkitCommandActor(player,
        new AddonMessageService(Map.of("en-US", Map.of())), Locale.US,
        (menuName, page) -> {});

    assertThat(new BukkitDisplayTargets().targetedSign(actor))
        .contains(new DisplayLocation(backingWorld.getUID(), 1, 65, 0));
    assertThat(ignoredPassableBlocks).isFalse();
  }

  @Test
  void rejectsFloorAndCeilingFramesAndIncompleteWalls() {
    assertThatThrownBy(() -> BukkitDisplayTargets.layout(BlockFace.UP,
        List.of(frame(0, 64, 0)), new MarketChartDimensions(1, 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BukkitDisplayTargets.layout(BlockFace.NORTH,
        List.of(frame(0, 64, 0)), new MarketChartDimensions(2, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolvesStoredFrameInsideItsRegionBeforeEnteringEntityScheduler() throws Exception {
    WorldMock world = server.addSimpleWorld("display-clear-world");
    ItemFrame frame = world.spawn(new Location(world, 1, 64, 1), ItemFrame.class);
    List<String> calls = new ArrayList<>();
    BukkitDisplayTargets targets = new BukkitDisplayTargets(new Access() {
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
      @Override public void runAtEntity(ItemFrame owner, Runnable action, Runnable retired) {
        calls.add("entity-scheduler"); action.run();
      }
      @Override public MapView createMap(World owner) { throw new AssertionError("unexpected"); }
    });

    targets.clearMapWall(new MapWallBinding(UUID.randomUUID(), "market", MarketChartMode.LINE,
        MarketChartPeriod.ONE_HOUR, new MarketChartDimensions(1, 1), List.of(
        new MapFrameBinding(frame.getUniqueId(), world.getUID(), 1, 64, 1, 7))))
        .get(5, TimeUnit.SECONDS);

    assertThat(calls).containsExactly("world", "location", "chunk", "entity", "entity-scheduler");
  }

  @Test
  void reportsAClearErrorWhenTheServerCannotCreateANewMapView() throws Exception {
    WorldMock world = server.addSimpleWorld("display-create-world");
    ItemFrame frame = proxy(ItemFrame.class, (method, arguments) -> switch (method.getName()) {
      case "getWorld" -> world;
      case "getLocation" -> new Location(world, 1, 64, 1);
      case "getUniqueId" -> UUID.randomUUID();
      case "getAttachedFace" -> BlockFace.EAST;
      case "getItem" -> new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR);
      case "isValid" -> true;
      default -> defaultValue(method.getReturnType());
    });
    BukkitDisplayTargets targets = new BukkitDisplayTargets(new Access() {
      @Override public World world(UUID worldId) { return world; }
      @Override public void runAtLocation(Location location, Runnable action) { action.run(); }
      @Override public boolean chunkLoaded(World owner, int chunkX, int chunkZ) { return true; }
      @Override public Entity entity(World owner, UUID entityId) { return frame; }
      @Override public void runAtEntity(ItemFrame owner, Runnable action, Runnable retired) {
        action.run();
      }
      @Override public MapView createMap(World owner) { return null; }
    });
    CompletableFuture<MarketDisplayAdministration.CreatedMapWall> result =
        new CompletableFuture<>();

    targets.createMaps(List.of(frame), 0, new ArrayList<>(), result);

    assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
        .hasRootCauseMessage("the server did not provide a new map view for " + world.getName());
  }

  private interface Access extends BukkitDisplayTargets.WorldAccess {}

  private static void assertLayout(
      BlockFace supportingFace,
      BukkitDisplayTargets.FramePosition topLeft,
      BukkitDisplayTargets.FramePosition topRight,
      BukkitDisplayTargets.FramePosition bottomLeft,
      BukkitDisplayTargets.FramePosition bottomRight) {
    List<BukkitDisplayTargets.FramePosition> available = List.of(
        bottomRight, topLeft, bottomLeft, topRight);

    assertThat(BukkitDisplayTargets.layout(supportingFace, available,
        new MarketChartDimensions(2, 2)))
        .containsExactly(topLeft, topRight, bottomLeft, bottomRight);
  }

  private static Block signBlock(WorldMock world, int x, int y, int z) {
    Block block = world.getBlockAt(x, y, z);
    return proxy(Block.class, (method, arguments) -> switch (method.getName()) {
      case "getState" -> proxy(Sign.class, (signMethod, signArguments) ->
          defaultValue(signMethod.getReturnType()));
      case "getWorld" -> world;
      case "getX" -> x;
      case "getY" -> y;
      case "getZ" -> z;
      default -> defaultValue(method.getReturnType());
    });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, Invocation invocation) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
        (ignored, method, arguments) -> invocation.invoke(method, arguments));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0.0F;
    if (type == double.class) return 0.0D;
    if (type == char.class) return '\0';
    throw new IllegalArgumentException("unsupported primitive: " + type);
  }

  private static BukkitDisplayTargets.FramePosition frame(int x, int y, int z) {
    return new BukkitDisplayTargets.FramePosition(x, y, z);
  }

  @FunctionalInterface
  private interface Invocation {
    Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
  }
}
