package com.ghostchu.quickshop.addon.exchange.display;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/** Protects managed displays and retries only bindings in chunks that become available. */
public final class MarketDisplayListener implements Listener {
  private final MarketDisplayRegistry registry;
  private final MarketDisplayService displays;

  public MarketDisplayListener(MarketDisplayRegistry registry, MarketDisplayService displays) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.displays = Objects.requireNonNull(displays, "displays");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEntityEvent event) {
    if (event.getRightClicked() instanceof ItemFrame frame && registry.managesFrame(frame.getUniqueId())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof ItemFrame frame && registry.managesFrame(frame.getUniqueId())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onHangingBreak(HangingBreakEvent event) {
    if (event.getEntity() instanceof ItemFrame frame && registry.managesFrame(frame.getUniqueId())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSignChange(SignChangeEvent event) {
    if (registry.managesSign(location(event.getBlock()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (registry.managesSign(location(event.getBlock()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    removeManagedSigns(event.blockList());
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    removeManagedSigns(event.blockList());
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPistonExtend(BlockPistonExtendEvent event) {
    if (touchesManagedSign(event.getBlocks(), event.getDirection().getModX(),
        event.getDirection().getModY(), event.getDirection().getModZ())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPistonRetract(BlockPistonRetractEvent event) {
    if (touchesManagedSign(event.getBlocks(), -event.getDirection().getModX(),
        -event.getDirection().getModY(), -event.getDirection().getModZ())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChunkLoad(ChunkLoadEvent event) {
    UUID worldId = event.getWorld().getUID();
    int chunkX = event.getChunk().getX();
    int chunkZ = event.getChunk().getZ();
    registry.mapWallsInChunk(worldId, chunkX, chunkZ).forEach(displays::refresh);
    registry.signsInChunk(worldId, chunkX, chunkZ).forEach(displays::refresh);
  }

  private void removeManagedSigns(List<Block> blocks) {
    blocks.removeIf(block -> registry.managesSign(location(block)));
  }

  private boolean touchesManagedSign(List<Block> blocks, int offsetX, int offsetY, int offsetZ) {
    for (Block block : blocks) {
      if (registry.managesSign(location(block))
          || registry.managesSign(new DisplayLocation(block.getWorld().getUID(),
              block.getX() + offsetX, block.getY() + offsetY, block.getZ() + offsetZ))) {
        return true;
      }
    }
    return false;
  }

  private static DisplayLocation location(Block block) {
    return new DisplayLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
  }
}
