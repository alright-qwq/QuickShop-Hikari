package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.QuickShop;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Applies cached display output only on the owning Folia entity or region thread. */
public final class FoliaDisplayScheduler implements DisplayScheduler {
  private final Map<Integer, MarketMapRenderer> mapRenderers = new ConcurrentHashMap<>();
  private final WorldAccess access;

  public FoliaDisplayScheduler() {
    this(new BukkitWorldAccess());
  }

  FoliaDisplayScheduler(WorldAccess access) {
    this.access = Objects.requireNonNull(access, "access");
  }

  @Override
  public CompletableFuture<Void> updateMapFrame(MapFrameBinding frame, MarketChartImage image) {
    Objects.requireNonNull(frame, "frame");
    Objects.requireNonNull(image, "image");
    World world = access.world(frame.worldId());
    if (world == null) {
      return CompletableFuture.completedFuture(null);
    }
    Location location = new Location(world, frame.x(), frame.y(), frame.z());
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      access.runAtLocation(location, () -> resolveAndUpdateFrame(world, frame, image, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  private void resolveAndUpdateFrame(
      World world,
      MapFrameBinding frame,
      MarketChartImage image,
      CompletableFuture<Void> result) {
    try {
      if (!access.chunkLoaded(world, Math.floorDiv(frame.x(), 16),
          Math.floorDiv(frame.z(), 16))) {
        result.complete(null);
        return;
      }
      Entity entity = access.entity(world, frame.entityId());
      if (!(entity instanceof ItemFrame itemFrame)) {
        result.complete(null);
        return;
      }
      access.runAtEntity(itemFrame, () -> applyMapImage(itemFrame, frame, image, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  private void applyMapImage(
      ItemFrame itemFrame,
      MapFrameBinding frame,
      MarketChartImage image,
      CompletableFuture<Void> result) {
    try {
      if (!itemFrame.isValid()) {
        result.complete(null);
        return;
      }
      MapView map = access.map(frame.mapId());
      if (map == null) {
        result.complete(null);
        return;
      }
      MarketMapRenderer renderer = mapRenderers.computeIfAbsent(frame.mapId(), ignored -> {
        for (MapRenderer existing : List.copyOf(map.getRenderers())) {
          map.removeRenderer(existing);
        }
        MarketMapRenderer created = new MarketMapRenderer(image);
        map.addRenderer(created);
        map.setTrackingPosition(false);
        map.setUnlimitedTracking(false);
        map.setLocked(true);
        return created;
      });
      renderer.update(image);
      result.complete(null);
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  @Override
  public CompletableFuture<Void> updateSign(MarketSignBinding binding, MarketSignLines lines) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(lines, "lines");
    DisplayLocation stored = binding.location();
    World world = access.world(stored.worldId());
    if (world == null) {
      return CompletableFuture.completedFuture(null);
    }
    Location location = new Location(world, stored.x(), stored.y(), stored.z());
    CompletableFuture<Void> result = new CompletableFuture<>();
    try {
      access.runAtLocation(location, () -> applySignLines(world, stored, lines, result));
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
    return result;
  }

  private void applySignLines(
      World world,
      DisplayLocation stored,
      MarketSignLines lines,
      CompletableFuture<Void> result) {
    try {
      if (!access.chunkLoaded(world, Math.floorDiv(stored.x(), 16),
          Math.floorDiv(stored.z(), 16))) {
        result.complete(null);
        return;
      }
      if (!(world.getBlockAt(stored.x(), stored.y(), stored.z()).getState() instanceof Sign sign)) {
        result.complete(null);
        return;
      }
      for (int index = 0; index < lines.lines().size(); index++) {
        MarketSignLine line = lines.lines().get(index);
        sign.line(index, Component.text(line.text(), color(line.tone())));
      }
      sign.update(true, false);
      result.complete(null);
    } catch (RuntimeException failure) {
      result.completeExceptionally(failure);
    }
  }

  interface WorldAccess {
    World world(UUID worldId);

    void runAtLocation(Location location, Runnable action);

    boolean chunkLoaded(World world, int chunkX, int chunkZ);

    Entity entity(World world, UUID entityId);

    void runAtEntity(ItemFrame frame, Runnable action);

    MapView map(int mapId);
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
    public void runAtEntity(ItemFrame frame, Runnable action) {
      QuickShop.folia().getScheduler().runAtEntityLater(frame, action, 1L);
    }

    @Override
    public MapView map(int mapId) {
      return Bukkit.getMap(mapId);
    }
  }

  private static NamedTextColor color(MarketSignTone tone) {
    return switch (tone) {
      case RISE -> NamedTextColor.RED;
      case FALL -> NamedTextColor.GREEN;
      case FLAT -> NamedTextColor.GRAY;
      case STATUS -> NamedTextColor.GOLD;
      case NORMAL -> NamedTextColor.BLACK;
    };
  }
}
