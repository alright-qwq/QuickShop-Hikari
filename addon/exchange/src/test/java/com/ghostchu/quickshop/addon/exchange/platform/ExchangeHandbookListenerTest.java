package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.NamespacedKey;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeHandbookListenerTest {
  private static final NamespacedKey HANDBOOK_KEY =
      new NamespacedKey("exchange", "handbook-listener");
  private static ServerMock server;

  @BeforeAll
  static void startServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopServer() {
    MockBukkit.unmock();
  }

  @BeforeEach
  void clearPlayers() {
    java.util.List.copyOf(server.getOnlinePlayers()).forEach(player -> player.kick());
  }

  @Test
  void authenticatedMainHandRightClickCancelsAndSchedulesMarketOpenOnce() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService handbooks = service(true);
    AtomicInteger schedules = new AtomicInteger();
    AtomicInteger opens = new AtomicInteger();
    ExchangeHandbookListener listener = listener(
        handbooks,
        new RolloutPolicy(true, Set.of(player.getUniqueId())),
        ignored -> true,
        (owner, action) -> {
          assertThat(owner).isSameAs(player);
          schedules.incrementAndGet();
          action.run();
        },
        owner -> opens.incrementAndGet());
    PlayerInteractEvent event = event(
        player, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND, handbooks.createItem(player));

    listener.onInteract(event);

    assertThat(event.isCancelled()).isTrue();
    assertThat(schedules).hasValue(1);
    assertThat(opens).hasValue(1);
  }

  @Test
  void offHandLeftClickAndOrdinaryItemsDoNotTriggerOrCancel() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService handbooks = service(true);
    AtomicInteger opens = new AtomicInteger();
    ExchangeHandbookListener listener = listener(
        handbooks, RolloutPolicy.DISABLED, ignored -> true,
        (owner, action) -> action.run(), owner -> opens.incrementAndGet());
    ItemStack handbook = handbooks.createItem(player);
    PlayerInteractEvent offHand = event(
        player, Action.RIGHT_CLICK_AIR, EquipmentSlot.OFF_HAND, handbook);
    PlayerInteractEvent leftClick = event(
        player, Action.LEFT_CLICK_AIR, EquipmentSlot.HAND, handbook);
    PlayerInteractEvent ordinary = event(
        player, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND,
        new ItemStack(org.bukkit.Material.KNOWLEDGE_BOOK));
    boolean offHandInitiallyCancelled = offHand.isCancelled();
    boolean leftClickInitiallyCancelled = leftClick.isCancelled();
    boolean ordinaryInitiallyCancelled = ordinary.isCancelled();

    listener.onInteract(offHand);
    listener.onInteract(leftClick);
    listener.onInteract(ordinary);

    assertThat(offHand.isCancelled()).isEqualTo(offHandInitiallyCancelled);
    assertThat(leftClick.isCancelled()).isEqualTo(leftClickInitiallyCancelled);
    assertThat(ordinary.isCancelled()).isEqualTo(ordinaryInitiallyCancelled);
    assertThat(opens).hasValue(0);
  }

  @Test
  void disabledHandbookCancelsAuthenticatedUseButDoesNotOpen() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService enabled = service(true);
    ExchangeHandbookListener listener = listener(
        service(false), RolloutPolicy.DISABLED, ignored -> true,
        (owner, action) -> action.run(), owner -> {
          throw new AssertionError("disabled handbook must not open");
        });
    PlayerInteractEvent event = event(
        player, Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, enabled.createItem(player));

    listener.onInteract(event);

    assertThat(event.isCancelled()).isTrue();
    assertThat(player.nextMessage()).isEqualTo("Handbook disabled");
  }

  @Test
  void missingPermissionAndRolloutDenialFailClosedBeforeScheduling() {
    PlayerMock deniedPermission = server.addPlayer();
    PlayerMock deniedRollout = server.addPlayer();
    ExchangeHandbookService handbooks = service(true);
    AtomicInteger schedules = new AtomicInteger();
    ExchangeHandbookListener permissionListener = listener(
        handbooks, RolloutPolicy.DISABLED, ignored -> false,
        (owner, action) -> schedules.incrementAndGet(), owner -> {});
    ExchangeHandbookListener rolloutListener = listener(
        handbooks, new RolloutPolicy(true, Set.of()), ignored -> true,
        (owner, action) -> schedules.incrementAndGet(), owner -> {});
    PlayerInteractEvent permissionEvent = event(
        deniedPermission, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND,
        handbooks.createItem(deniedPermission));
    PlayerInteractEvent rolloutEvent = event(
        deniedRollout, Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND,
        handbooks.createItem(deniedRollout));

    permissionListener.onInteract(permissionEvent);
    rolloutListener.onInteract(rolloutEvent);

    assertThat(permissionEvent.isCancelled()).isTrue();
    assertThat(rolloutEvent.isCancelled()).isTrue();
    assertThat(deniedPermission.nextMessage()).isEqualTo("Permission denied");
    assertThat(deniedRollout.nextMessage()).isEqualTo("Rollout denied");
    assertThat(schedules).hasValue(0);
  }

  private static ExchangeHandbookListener listener(
      ExchangeHandbookService handbooks,
      RolloutPolicy rollout,
      java.util.function.Predicate<org.bukkit.entity.Player> usePermission,
      java.util.function.BiConsumer<org.bukkit.entity.Player, Runnable> scheduler,
      java.util.function.Consumer<org.bukkit.entity.Player> opener) {
    return new ExchangeHandbookListener(
        handbooks, rollout, messages(), ignored -> Locale.US,
        usePermission, scheduler, opener);
  }

  private static PlayerInteractEvent event(
      PlayerMock player, Action action, EquipmentSlot hand, ItemStack item) {
    return new PlayerInteractEvent(player, action, item, null, null, hand);
  }

  private static ExchangeHandbookService service(boolean enabled) {
    return new ExchangeHandbookService(
        HANDBOOK_KEY,
        ExchangeHandbookSettings.create(enabled, true, true, "KNOWLEDGE_BOOK"),
        messages(),
        ignored -> Locale.US);
  }

  private static AddonMessageService messages() {
    return new AddonMessageService(Map.of(
        "en-US", Map.of(
            "handbook-title", "Exchange Trading Handbook",
            "handbook-lore-1", "Right click to open Exchange",
            "handbook-lore-2", "This signed handbook can be traded",
            "handbook-disabled", "Handbook disabled",
            "permission-denied", "Permission denied",
            "rollout-not-allowed", "Rollout denied")));
  }
}
