package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Prevents marked Exchange custody stacks from leaving or being mutated in their owner inventory. */
public final class TransferMarkerListener implements Listener {
  private final NamespacedKey transferMarker;

  public TransferMarkerListener(NamespacedKey transferMarker) {
    this.transferMarker = Objects.requireNonNull(transferMarker, "transferMarker");
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    if (shouldBlock(event.getItemDrop().getItemStack())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (shouldBlock(event.getItem().getItemStack())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onClick(InventoryClickEvent event) {
    if (shouldBlock(event.getCurrentItem()) || shouldBlock(event.getCursor())) {
      event.setCancelled(true);
      return;
    }
    int hotbar = event.getHotbarButton();
    if (hotbar >= 0 && shouldBlock(event.getWhoClicked().getInventory().getItem(hotbar))) {
      event.setCancelled(true);
      return;
    }
    if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
        && (containsMarkedItem(event.getView().getTopInventory())
            || containsMarkedItem(event.getView().getBottomInventory()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onCreative(InventoryCreativeEvent event) {
    if (shouldBlock(event.getCurrentItem()) || shouldBlock(event.getCursor())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDrag(InventoryDragEvent event) {
    if (shouldBlock(event.getOldCursor()) || shouldBlock(event.getCursor())
        || event.getNewItems().values().stream().anyMatch(this::shouldBlock)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onMove(InventoryMoveItemEvent event) {
    if (shouldBlock(event.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (shouldBlock(event.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    ItemStack held = event.getHand() == EquipmentSlot.OFF_HAND
        ? event.getPlayer().getInventory().getItemInOffHand()
        : event.getPlayer().getInventory().getItemInMainHand();
    if (shouldBlock(held)
        || event.getRightClicked() instanceof ItemFrame frame && shouldBlock(frame.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
    if (shouldBlock(event.getPlayerItem()) || shouldBlock(event.getArmorStandItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onConsume(PlayerItemConsumeEvent event) {
    if (shouldBlock(event.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    if (shouldBlock(event.getItemInHand())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSwap(PlayerSwapHandItemsEvent event) {
    if (shouldBlock(event.getMainHandItem()) || shouldBlock(event.getOffHandItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDamage(PlayerItemDamageEvent event) {
    if (shouldBlock(event.getItem())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void onDeath(PlayerDeathEvent event) {
    if (retainMarkedCustodyOnDeath(event.getEntity().getInventory(), event.getDrops())) {
      event.setKeepInventory(true);
    }
  }

  boolean shouldBlock(ItemStack stack) {
    return FoliaInventoryGateway.hasTransferMarker(stack, transferMarker);
  }

  private boolean containsMarkedItem(Inventory inventory) {
    for (ItemStack stack : inventory.getContents()) {
      if (shouldBlock(stack)) {
        return true;
      }
    }
    return false;
  }

  boolean retainMarkedCustodyOnDeath(PlayerInventory inventory, List<ItemStack> drops) {
    List<ItemStack> marked = new ArrayList<>();
    for (ItemStack stack : inventory.getContents()) {
      if (shouldBlock(stack)) {
        marked.add(stack.clone());
      }
    }
    if (marked.isEmpty()) {
      return false;
    }
    drops.removeIf(this::shouldBlock);
    inventory.clear();
    for (ItemStack stack : marked) {
      inventory.addItem(stack);
    }
    return true;
  }
}
