package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferMarkerListenerTest {
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
  void recognisesAnyExchangeTransferMarkerWithoutCallerSuppliedTransferId() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    ItemStack marked = markedStack(marker, 2);

    assertThat(FoliaInventoryGateway.hasTransferMarker(marked, marker)).isTrue();
    assertThat(FoliaInventoryGateway.hasTransferMarker(
        new ItemStack(Material.DIAMOND), marker)).isFalse();
  }

  @Test
  void blocksMarkedCustodyStacksButLeavesOrdinaryItemsUnrestricted() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);

    assertThat(listener.shouldBlock(markedStack(marker, 1))).isTrue();
    assertThat(listener.shouldBlock(new ItemStack(Material.DIAMOND))).isFalse();
    assertThat(listener.shouldBlock(null)).isFalse();
  }

  @Test
  void cancelsInventoryClickForMarkedCustody() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 1);
    player.getInventory().setItem(0, marked);
    InventoryView view = inventoryView(player);

    InventoryClickEvent click = new InventoryClickEvent(
        view, InventoryType.SlotType.CONTAINER, 0,
        ClickType.LEFT, InventoryAction.PICKUP_ALL);
    listener.onClick(click);

    assertThat(click.isCancelled()).isTrue();
  }

  @Test
  void cancelsPlayerUseAndMutationForMarkedCustody() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 1);

    PlayerInteractEvent interact = new PlayerInteractEvent(
        player, Action.RIGHT_CLICK_AIR, marked, null, null, EquipmentSlot.HAND);
    listener.onInteract(interact);
    PlayerItemConsumeEvent consume = new PlayerItemConsumeEvent(player, marked, EquipmentSlot.HAND);
    listener.onConsume(consume);
    PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(
        player, marked, new ItemStack(Material.AIR));
    listener.onSwap(swap);
    PlayerItemDamageEvent damage = new PlayerItemDamageEvent(player, marked, 1);
    listener.onDamage(damage);

    assertThat(interact.isCancelled()).isTrue();
    assertThat(consume.isCancelled()).isTrue();
    assertThat(swap.isCancelled()).isTrue();
    assertThat(damage.isCancelled()).isTrue();
  }

  @Test
  void preventsMarkedCustodyFromBeingPlacedOnEntities() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 1);
    player.getInventory().setItemInMainHand(marked);
    ArmorStand armorStand = player.getWorld().spawn(player.getLocation(), ArmorStand.class);
    PlayerInteractEntityEvent interact = new PlayerInteractEntityEvent(
        player, armorStand, EquipmentSlot.HAND);
    PlayerArmorStandManipulateEvent manipulate = new PlayerArmorStandManipulateEvent(
        player, armorStand, marked, new ItemStack(Material.AIR),
        EquipmentSlot.HEAD, EquipmentSlot.HAND);

    listener.onInteractEntity(interact);
    listener.onArmorStandManipulate(manipulate);

    assertThat(interact.isCancelled()).isTrue();
    assertThat(manipulate.isCancelled()).isTrue();
  }

  @Test
  void preventsMarkedCustodyFromBeingRemovedFromItemFrames() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
    ItemFrame itemFrame = player.getWorld().spawn(player.getLocation(), ItemFrame.class);
    itemFrame.setItem(markedStack(marker, 1));
    PlayerInteractEntityEvent interact = new PlayerInteractEntityEvent(
        player, itemFrame, EquipmentSlot.HAND);

    listener.onInteractEntity(interact);

    assertThat(interact.isCancelled()).isTrue();
  }

  @Test
  void permitsOrdinaryItemsDuringEntityInteraction() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack ordinary = new ItemStack(Material.DIAMOND);
    player.getInventory().setItemInOffHand(ordinary);
    ArmorStand armorStand = player.getWorld().spawn(player.getLocation(), ArmorStand.class);
    PlayerInteractEntityEvent interact = new PlayerInteractEntityEvent(
        player, armorStand, EquipmentSlot.OFF_HAND);
    PlayerArmorStandManipulateEvent manipulate = new PlayerArmorStandManipulateEvent(
        player, armorStand, ordinary, new ItemStack(Material.AIR),
        EquipmentSlot.HEAD, EquipmentSlot.OFF_HAND);

    listener.onInteractEntity(interact);
    listener.onArmorStandManipulate(manipulate);

    assertThat(interact.isCancelled()).isFalse();
    assertThat(manipulate.isCancelled()).isFalse();
  }

  @Test
  void preventsMarkedCustodyFromBeingDroppedOrPickedUp() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    Item item = player.getWorld().dropItem(player.getLocation(), markedStack(marker, 1));
    PlayerDropItemEvent drop = new PlayerDropItemEvent(player, item);
    EntityPickupItemEvent pickup = new EntityPickupItemEvent(player, item, 0);

    listener.onDrop(drop);
    listener.onPickup(pickup);

    assertThat(drop.isCancelled()).isTrue();
    assertThat(pickup.isCancelled()).isTrue();
  }

  @Test
  void cancelsCollectToCursorWhenItCouldSweepUpMarkedCustody() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
    player.getInventory().setItem(1, markedStack(marker, 1));
    InventoryView view = inventoryView(player);
    view.setCursor(new ItemStack(Material.DIAMOND, 1));
    InventoryClickEvent collect = new InventoryClickEvent(
        view, InventoryType.SlotType.CONTAINER, 0,
        ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);

    listener.onClick(collect);

    assertThat(collect.isCancelled()).isTrue();
  }

  @Test
  void cancelsHotbarCreativeDragAndAutomatedMovesForMarkedCustody() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 1);
    player.getInventory().setItem(2, marked);
    InventoryView view = inventoryView(player);

    InventoryClickEvent hotbar = new InventoryClickEvent(
        view, InventoryType.SlotType.CONTAINER, 0,
        ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 2);
    InventoryCreativeEvent creative = new InventoryCreativeEvent(
        view, InventoryType.SlotType.CONTAINER, 0, marked);
    InventoryDragEvent drag = new InventoryDragEvent(
        view, marked, marked, false, Map.of(0, marked));
    Inventory source = server.createInventory(null, InventoryType.HOPPER);
    Inventory destination = server.createInventory(null, InventoryType.HOPPER);
    InventoryMoveItemEvent move = new InventoryMoveItemEvent(
        source, marked, destination, true);

    listener.onClick(hotbar);
    listener.onCreative(creative);
    listener.onDrag(drag);
    listener.onMove(move);

    assertThat(hotbar.isCancelled()).isTrue();
    assertThat(creative.isCancelled()).isTrue();
    assertThat(drag.isCancelled()).isTrue();
    assertThat(move.isCancelled()).isTrue();
  }

  @Test
  void cancelsBlockPlacementForMarkedCustody() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 1);
    Block placed = player.getWorld().getBlockAt(0, 64, 0);
    Block against = player.getWorld().getBlockAt(0, 63, 0);
    BlockPlaceEvent place = new BlockPlaceEvent(
        placed, placed.getState(), against, marked, player, true, EquipmentSlot.HAND);

    listener.onPlace(place);

    assertThat(place.isCancelled()).isTrue();
  }

  @Test
  void deathEventKeepsMarkedCustodyAndDropsOrdinaryItems() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 2);
    ItemStack ordinary = new ItemStack(Material.STONE, 3);
    player.getInventory().setItem(0, marked.clone());
    player.getInventory().setItem(1, ordinary.clone());
    List<ItemStack> drops = new ArrayList<>(List.of(marked.clone(), ordinary.clone()));
    PlayerDeathEvent death = new PlayerDeathEvent(
        player, DamageSource.builder(DamageType.GENERIC).build(), drops, 0, "test");

    listener.onDeath(death);

    assertThat(death.getKeepInventory()).isTrue();
    assertThat(player.getInventory().getItem(0)).isEqualTo(marked);
    assertThat(player.getInventory().getItem(1)).isNull();
    assertThat(death.getDrops()).containsExactly(ordinary);
  }

  @Test
  void deathKeepsOnlyMarkedCustodyWhileOrdinaryItemsStillDrop() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack marked = markedStack(marker, 2);
    ItemStack ordinary = new ItemStack(Material.STONE, 3);
    player.getInventory().setItem(0, marked.clone());
    player.getInventory().setItem(1, ordinary.clone());
    List<ItemStack> drops = new ArrayList<>(List.of(marked.clone(), ordinary.clone()));

    boolean keepInventory = listener.retainMarkedCustodyOnDeath(
        player.getInventory(), drops);

    assertThat(keepInventory).isTrue();
    assertThat(player.getInventory().getItem(0)).isEqualTo(marked);
    assertThat(player.getInventory().getItem(1)).isNull();
    assertThat(drops).containsExactly(ordinary);
  }

  @Test
  void ordinaryDeathInventoryIsNotModified() {
    NamespacedKey marker = new NamespacedKey("exchange", "transfer");
    TransferMarkerListener listener = new TransferMarkerListener(marker);
    PlayerMock player = server.addPlayer();
    ItemStack ordinary = new ItemStack(Material.STONE, 3);
    player.getInventory().setItem(0, ordinary.clone());
    List<ItemStack> drops = new ArrayList<>(List.of(ordinary.clone()));

    boolean keepInventory = listener.retainMarkedCustodyOnDeath(
        player.getInventory(), drops);

    assertThat(keepInventory).isFalse();
    assertThat(player.getInventory().getItem(0)).isEqualTo(ordinary);
    assertThat(drops).containsExactly(ordinary);
  }

  private static InventoryView inventoryView(PlayerMock player) {
    return new InventoryView() {
      private ItemStack cursor;

      @Override public Inventory getTopInventory() { return player.getInventory(); }
      @Override public Inventory getBottomInventory() { return player.getInventory(); }
      @Override public PlayerMock getPlayer() { return player; }
      @Override public InventoryType getType() { return InventoryType.PLAYER; }
      @Override public void setItem(int slot, ItemStack item) {
        player.getInventory().setItem(slot, item);
      }
      @Override public ItemStack getItem(int slot) {
        return player.getInventory().getItem(slot);
      }
      @Override public void setCursor(ItemStack item) { cursor = item; }
      @Override public ItemStack getCursor() { return cursor; }
      @Override public Inventory getInventory(int rawSlot) { return player.getInventory(); }
      @Override public int convertSlot(int rawSlot) { return rawSlot; }
      @Override public InventoryType.SlotType getSlotType(int slot) {
        return InventoryType.SlotType.CONTAINER;
      }
      @Override public void close() {}
      @Override public int countSlots() { return player.getInventory().getSize(); }
      @Override public boolean setProperty(Property property, int value) { return false; }
      @Override public String getTitle() { return "Player"; }
      @Override public String getOriginalTitle() { return "Player"; }
      @Override public void setTitle(String title) {}
    };
  }

  private static ItemStack markedStack(NamespacedKey marker, int amount) {
    ItemStack stack = new ItemStack(Material.DIAMOND, amount);
    stack.editMeta(meta -> meta.getPersistentDataContainer()
        .set(marker, PersistentDataType.STRING, UUID.randomUUID().toString()));
    return stack;
  }
}
