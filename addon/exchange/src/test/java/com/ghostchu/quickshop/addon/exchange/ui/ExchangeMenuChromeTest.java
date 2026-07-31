package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.tnemc.menu.core.Menu;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.compatibility.PlayerInventory;
import net.tnemc.menu.core.handlers.MenuClickHandler;
import net.tnemc.menu.core.icon.Icon;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.utils.SlotPos;
import net.tnemc.menu.core.viewer.CoreStatus;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ExchangeMenuChromeTest {
  @Test
  void formatsConfiguredShanghaiTimeAndCanBeDisabled() {
    Clock fixed = Clock.fixed(Instant.parse("2026-07-31T04:30:00Z"), ZoneOffset.UTC);
    ExchangeClockDisplay enabled = ExchangeClockDisplay.create(
        true, "Asia/Shanghai", "yyyy-MM-dd HH:mm", fixed, ignored -> {});
    ExchangeClockDisplay disabled = ExchangeClockDisplay.create(
        false, "Asia/Shanghai", "yyyy-MM-dd HH:mm", fixed, ignored -> {});

    assertThat(enabled.now()).contains(new ExchangeClockDisplay.DisplayTime(
        "2026-07-31 12:30", "Asia/Shanghai"));
    assertThat(disabled.now()).isEmpty();
  }

  @Test
  void invalidClockConfigurationFallsBackAndWarnsOnlyDuringCreation() {
    java.util.List<String> warnings = new java.util.ArrayList<>();
    Clock fixed = Clock.fixed(Instant.parse("2026-07-31T04:30:00Z"), ZoneOffset.UTC);

    ExchangeClockDisplay display = ExchangeClockDisplay.create(
        true, "Not/AZone", "bad pattern [", fixed, warnings::add);

    assertThat(display.now()).contains(new ExchangeClockDisplay.DisplayTime(
        "2026-07-31 12:30", "Asia/Shanghai"));
    assertThat(warnings).hasSize(2);
    display.now();
    assertThat(warnings).hasSize(2);
  }

  @Test
  void keepsPrimaryNavigationInStableBottomRowSlots() {
    assertThat(ExchangeMenuChrome.primaryNavigation(ExchangeMenuPage.MARKETS))
        .extracting(ExchangeMenuChrome.NavigationItem::slot,
            ExchangeMenuChrome.NavigationItem::destination,
            ExchangeMenuChrome.NavigationItem::active)
        .containsExactly(
            tuple(45, ExchangeMenuPage.MARKETS, true),
            tuple(47, ExchangeMenuPage.ORDERS, false),
            tuple(49, ExchangeMenuPage.ASSETS, false),
            tuple(51, ExchangeMenuPage.HISTORY, false));
  }

  @Test
  void confirmationPagesReturnToTheRelevantWorkflow() {
    assertThat(ExchangeMenuChrome.backDestination(ExchangeMenuPage.MARKET_DETAIL))
        .isEqualTo(ExchangeMenuPage.MARKETS);
    assertThat(ExchangeMenuChrome.backDestination(ExchangeMenuPage.ORDER_CONFIRM))
        .isEqualTo(ExchangeMenuPage.MARKET_DETAIL);
    assertThat(ExchangeMenuChrome.backDestination(ExchangeMenuPage.CANCEL_CONFIRM))
        .isEqualTo(ExchangeMenuPage.ORDERS);
    assertThat(ExchangeMenuChrome.backDestination(ExchangeMenuPage.TRANSFER_CONFIRM))
        .isEqualTo(ExchangeMenuPage.ASSETS);
  }

  @Test
  void orderConfirmationRebuildsMarketRequestWhenReturningToMarketDetail() {
    ExchangeMenuRequest retained = ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), UUID.randomUUID(), "minecraft_diamond/default",
        com.ghostchu.quickshop.addon.exchange.core.model.OrderSide.BUY,
        com.ghostchu.quickshop.addon.exchange.core.model.OrderType.LIMIT,
        java.math.BigDecimal.TEN, null, 2));

    assertThat(ExchangeMenuChrome.backRequest(ExchangeMenuPage.ORDER_CONFIRM, retained))
        .isEqualTo(ExchangeMenuRequest.market("minecraft_diamond/default"));
  }

  @Test
  void contentSlotsAvoidHeaderAndNavigationRows() {
    assertThat(ExchangeMenuChrome.CONTENT_SLOTS)
        .containsExactly(10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43)
        .allMatch(slot -> slot >= 9 && slot < 45);
  }

  @Test
  void addingIconsPreservesEarlierPlayerSpecificSlots() {
    PlayerInstancePage page = new PlayerInstancePage(1);
    UUID playerId = UUID.randomUUID();
    Icon navigation = new Icon(null, null);
    navigation.setSlot(45);
    Icon market = new Icon(null, null);
    market.setSlot(10);

    ExchangePageIcons.add(page, playerId, navigation);
    ExchangePageIcons.add(page, playerId, market);

    assertThat(page.getIcons(playerId))
        .containsEntry(45, navigation)
        .containsEntry(10, market)
        .hasSize(2);
  }

  @Test
  void switchingStatusIsRequiredOnlyWhenAViewerAlreadyExists() {
    assertThat(ExchangeMenuNavigator.switchingStatus(false)).isNull();
    assertThat(ExchangeMenuNavigator.switchingStatus(true)).isEqualTo(CoreStatus.SWITCHING);
  }

  @Test
  void pageNavigationMarksViewerAsSwitchingBeforeOpeningTheReplacementInventory() {
    UUID playerId = UUID.randomUUID();
    MenuViewer viewer = new MenuViewer(playerId);
    net.tnemc.menu.core.manager.MenuManager.instance().addViewer(viewer);
    AtomicInteger opens = new AtomicInteger();
    PlayerInventory<Object> inventory = new PlayerInventory<>() {
      @Override
      public UUID player() {
        return playerId;
      }

      @Override
      public Object build(MenuPlayer player, Menu menu, int page) {
        return new Object();
      }

      @Override
      public void openInventory(Object inventory) {
        opens.incrementAndGet();
      }

      @Override
      public void updateInventory(int slot, net.tnemc.item.AbstractItemStack<?> item) {}

      @Override
      public void close() {}
    };
    Menu exchange = new Menu();
    exchange.setName(ExchangeMenu.NAME);
    exchange.setRows(6);
    exchange.addPage(new PlayerInstancePage(ExchangeMenuPage.ASSETS.page()));
    net.tnemc.menu.core.manager.MenuManager.instance().addMenu(exchange);
    MenuPlayer player = new MenuPlayer() {
      @Override
      public UUID identifier() {
        return playerId;
      }

      @Override
      public PlayerInventory<?> inventory() {
        return inventory;
      }

      @Override
      public boolean hasPermission(String permission) {
        return true;
      }

      @Override
      public void message(String message) {}
    };

    ExchangeMenuNavigator.open(player, ExchangeMenuPage.ASSETS);

    assertThat(viewer.status()).isEqualTo(CoreStatus.SWITCHING);
    assertThat(opens).hasValue(1);
    net.tnemc.menu.core.manager.MenuManager.instance().removeViewer(playerId);
  }

  @Test
  void basePageClickEntryDispatchesPlayerSpecificIconAction() {
    UUID playerId = UUID.randomUUID();
    PlayerInstancePage page = ExchangePlayerPage.create(1);
    AtomicInteger clicks = new AtomicInteger();
    Icon icon = new Icon(null, null);
    icon.setSlot(45);
    icon.addAction(new RunnableAction(ignored -> clicks.incrementAndGet()));
    ExchangePageIcons.add(page, playerId, icon);

    MenuPlayer player = new MenuPlayer() {
      @Override
      public UUID identifier() {
        return playerId;
      }

      @Override
      public PlayerInventory<?> inventory() {
        return null;
      }

      @Override
      public boolean hasPermission(String permission) {
        return true;
      }

      @Override
      public void message(String message) {}
    };
    Menu menu = new Menu();
    menu.setRows(6);
    menu.addPage(page);
    MenuClickHandler click = new MenuClickHandler(
        new SlotPos(45), player, menu, 1, ActionType.LEFT_CLICK);

    boolean cancelled = menu.onClick(click);

    assertThat(cancelled).isTrue();
    assertThat(clicks).hasValue(1);
  }
}
