package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.command.CommandActor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** Resolves player targets and performs frame-owned map creation and cleanup. */
public final class BukkitDisplayTargets implements MarketDisplayAdministration.Targets {
  private static final double TARGET_DISTANCE = 8.0D;
  private final WorldAccess access;

  public BukkitDisplayTargets() {
    this(new BukkitWorldAccess());
  }

  BukkitDisplayTargets(WorldAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  @Override
  public CompletableFuture<MarketDisplayAdministration.CreatedMapWall> createMapWall(
      CommandActor actor, MarketChartDimensions dimensions) {
    Player player = player(actor);
    CompletableFuture<MarketDisplayAdministration.CreatedMapWall> result = new CompletableFuture<>();
    QuickShop.folia().getScheduler().runAtEntityLater(player,
        () -> resolveAndCreate(player, dimensions, result), () -> result.completeExceptionally(
            new IllegalStateException("player is no longer available")), 1L);
    return result;
  }

  @Override
  public Optional<UUID> targetedFrame(CommandActor actor) {
    return targetFrame(player(actor)).map(Entity::getUniqueId);
  }

  @Override
  public Optional<DisplayLocation> targetedSign(CommandActor actor) {
    Player player = player(actor);
    Location eye = player.getEyeLocation();
    RayTraceResult trace = player.getWorld().rayTraceBlocks(eye, eye.getDirection(),
        TARGET_DISTANCE, FluidCollisionMode.NEVER, false);
    Block block = trace == null ? null : trace.getHitBlock();
    if (block == null || !(block.getState() instanceof Sign)) {
      return Optional.empty();
    }
    return Optional.of(new DisplayLocation(block.getWorld().getUID(),
        block.getX(), block.getY(), block.getZ()));
  }

  @Override
  public CompletableFuture<Void> clearMapWall(MapWallBinding binding) {
    CompletableFuture<?>[] updates = binding.frames().stream()
        .map(this::clearFrame).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(updates);
  }

  private void resolveAndCreate(Player player, MarketChartDimensions dimensions,
                                CompletableFuture<MarketDisplayAdministration.CreatedMapWall> result) {
    try {
      ItemFrame target = targetFrame(player).orElseThrow(
          () -> new IllegalArgumentException("no item frame target"));
      BlockFace attachedFace = target.getAttachedFace();
      List<FramePosition> positions = layout(attachedFace,
          nearbyFrames(target, dimensions).stream().map(BukkitDisplayTargets::position).toList(),
          dimensions);
      Map<FramePosition, ItemFrame> byPosition = new HashMap<>();
      for (ItemFrame frame : nearbyFrames(target, dimensions)) {
        if (frame.getAttachedFace() == attachedFace) {
          byPosition.put(position(frame), frame);
        }
      }
      List<ItemFrame> frames = positions.stream().map(position -> Optional.ofNullable(
          byPosition.get(position)).orElseThrow(() -> new IllegalArgumentException(
              "incomplete item frame wall"))).toList();
      validateEmpty(frames);
      createMaps(frames, 0, new ArrayList<>(), result);
    } catch (Exception failure) {
      result.completeExceptionally(failure);
    }
  }

  private void createMaps(List<ItemFrame> frames, int index, List<MapFrameBinding> created,
                          CompletableFuture<MarketDisplayAdministration.CreatedMapWall> result) {
    if (index >= frames.size()) {
      List<MapFrameBinding> bindings = List.copyOf(created);
      result.complete(new MarketDisplayAdministration.CreatedMapWall(bindings,
          () -> clearFrames(bindings)));
      return;
    }
    ItemFrame frame = frames.get(index);
    QuickShop.folia().getScheduler().runAtEntityLater(frame, () -> {
      try {
        if (!frame.isValid() || !frame.getItem().getType().isAir()) {
          throw new IllegalStateException("item frame is unavailable or occupied");
        }
        World world = frame.getWorld();
        MapView map = access.createMap(world);
        prepareMap(map);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(map);
        item.setItemMeta(meta);
        frame.setItem(item, false);
        frame.setItemDropChance(0.0F);
        Location location = frame.getLocation();
        created.add(new MapFrameBinding(frame.getUniqueId(), world.getUID(),
            location.getBlockX(), location.getBlockY(), location.getBlockZ(), map.getId()));
        createMaps(frames, index + 1, created, result);
      } catch (Exception failure) {
        clearFrames(List.copyOf(created)).whenComplete((ignored, rollbackFailure) -> {
          if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
          result.completeExceptionally(failure);
        });
      }
    }, () -> {
      IllegalStateException failure = new IllegalStateException(
          "item frame is no longer available");
      clearFrames(List.copyOf(created)).whenComplete((ignored, rollbackFailure) -> {
        if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
        result.completeExceptionally(failure);
      });
    }, 1L);
  }

  private CompletableFuture<Void> clearFrames(List<MapFrameBinding> frames) {
    CompletableFuture<?>[] updates = frames.stream()
        .map(this::clearFrame).toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(updates);
  }

  private CompletableFuture<Void> clearFrame(MapFrameBinding stored) {
    World world = access.world(stored.worldId());
    if (world == null) return CompletableFuture.completedFuture(null);
    CompletableFuture<Void> result = new CompletableFuture<>();
    Location location = new Location(world, stored.x(), stored.y(), stored.z());
    try {
      access.runAtLocation(location, () -> resolveAndClearFrame(world, stored, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  private void resolveAndClearFrame(World world, MapFrameBinding stored,
                                    CompletableFuture<Void> result) {
    try {
      if (!access.chunkLoaded(world, Math.floorDiv(stored.x(), 16),
          Math.floorDiv(stored.z(), 16))) {
        result.complete(null);
        return;
      }
      Entity entity = access.entity(world, stored.entityId());
      if (!(entity instanceof ItemFrame frame)) {
        result.complete(null);
        return;
      }
      access.runAtEntity(frame, () -> clearOwnedMap(frame, stored, result),
          () -> result.complete(null));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  private static void clearOwnedMap(ItemFrame frame, MapFrameBinding stored,
                                    CompletableFuture<Void> result) {
    try {
      ItemStack item = frame.getItem();
      if (item.getType() == Material.FILLED_MAP && item.getItemMeta() instanceof MapMeta meta
          && meta.hasMapView() && meta.getMapView().getId() == stored.mapId()) {
        frame.setItem(new ItemStack(Material.AIR), false);
      }
      result.complete(null);
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  private static Optional<ItemFrame> targetFrame(Player player) {
    Location eye = player.getEyeLocation();
    RayTraceResult trace = player.getWorld().rayTraceEntities(eye, eye.getDirection(),
        TARGET_DISTANCE, 0.25D, entity -> entity instanceof ItemFrame);
    return trace != null && trace.getHitEntity() instanceof ItemFrame frame
        ? Optional.of(frame) : Optional.empty();
  }

  private static List<ItemFrame> nearbyFrames(ItemFrame target, MarketChartDimensions dimensions) {
    int radius = Math.max(dimensions.columns(), dimensions.rows()) + 1;
    return target.getWorld().getNearbyEntities(target.getLocation(), radius, radius, radius,
        entity -> entity instanceof ItemFrame frame
            && frame.getAttachedFace() == target.getAttachedFace())
        .stream().map(ItemFrame.class::cast).toList();
  }

  private static void validateEmpty(List<ItemFrame> frames) {
    if (frames.stream().anyMatch(frame -> !frame.getItem().getType().isAir())) {
      throw new IllegalArgumentException("item frame wall contains occupied frames");
    }
  }

  private static void prepareMap(MapView map) {
    for (MapRenderer renderer : List.copyOf(map.getRenderers())) {
      map.removeRenderer(renderer);
    }
    map.setTrackingPosition(false);
    map.setUnlimitedTracking(false);
    map.setLocked(true);
  }

  interface WorldAccess {
    World world(UUID worldId);

    void runAtLocation(Location location, Runnable action);

    boolean chunkLoaded(World world, int chunkX, int chunkZ);

    Entity entity(World world, UUID entityId);

    void runAtEntity(ItemFrame frame, Runnable action, Runnable retired);

    MapView createMap(World world);
  }

  private static final class BukkitWorldAccess implements WorldAccess {
    @Override
    public World world(UUID worldId) {
      return Bukkit.getWorld(worldId);
    }

    @Override
    public void runAtLocation(Location location, Runnable action) {
      QuickShop.folia().getScheduler().runAtLocation(location, ignored -> action.run());
    }

    @Override
    public boolean chunkLoaded(World world, int chunkX, int chunkZ) {
      return world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public Entity entity(World world, UUID entityId) {
      return world.getEntity(entityId);
    }

    @Override
    public void runAtEntity(ItemFrame frame, Runnable action, Runnable retired) {
      QuickShop.folia().getScheduler().runAtEntityLater(frame, action, retired, 1L);
    }

    @Override
    public MapView createMap(World world) {
      return Bukkit.createMap(world);
    }
  }

  static List<FramePosition> layout(BlockFace attachedFace, List<FramePosition> available,
                                    MarketChartDimensions dimensions) {
    Objects.requireNonNull(attachedFace, "attachedFace");
    Objects.requireNonNull(available, "available");
    Objects.requireNonNull(dimensions, "dimensions");
    if (attachedFace == BlockFace.UP || attachedFace == BlockFace.DOWN
        || attachedFace.getModY() != 0) {
      throw new IllegalArgumentException("only vertical item-frame walls are supported");
    }
    if (available.isEmpty()) throw new IllegalArgumentException("item frame wall is empty");
    Vector right = rightVector(attachedFace);
    int top = available.stream().mapToInt(FramePosition::y).max().orElseThrow();
    int left = available.stream().mapToInt(position -> horizontal(position, right)).min()
        .orElseThrow();
    Map<String, FramePosition> indexed = new HashMap<>();
    for (FramePosition position : available) {
      indexed.put(horizontal(position, right) + ":" + position.y(), position);
    }
    List<FramePosition> result = new ArrayList<>();
    for (int row = 0; row < dimensions.rows(); row++) {
      for (int column = 0; column < dimensions.columns(); column++) {
        FramePosition position = indexed.get((left + column) + ":" + (top - row));
        if (position == null) throw new IllegalArgumentException("incomplete item frame wall");
        result.add(position);
      }
    }
    return List.copyOf(result);
  }

  private static Vector rightVector(BlockFace attachedFace) {
    return switch (attachedFace) {
      case SOUTH -> new Vector(-1, 0, 0);
      case NORTH -> new Vector(1, 0, 0);
      case EAST -> new Vector(0, 0, 1);
      case WEST -> new Vector(0, 0, -1);
      default -> throw new IllegalArgumentException("unsupported attached face: " + attachedFace);
    };
  }

  private static int horizontal(FramePosition position, Vector right) {
    return position.x() * right.getBlockX() + position.z() * right.getBlockZ();
  }

  private static FramePosition position(ItemFrame frame) {
    Location location = frame.getLocation();
    return new FramePosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private static Player player(CommandActor actor) {
    if (actor instanceof BukkitCommandActor bukkit) return bukkit.player();
    throw new IllegalArgumentException("display command requires a Bukkit player actor");
  }

  public record FramePosition(int x, int y, int z) {
  }
}
