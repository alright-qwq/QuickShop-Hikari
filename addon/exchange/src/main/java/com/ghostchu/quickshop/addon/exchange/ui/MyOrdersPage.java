package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays the first bounded page of a player's currently cancellable orders. */
final class MyOrdersPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;

  MyOrdersPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
               AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    views.accountOrders(playerId, 36, 0).whenComplete((orders, failure) -> {
      if (opened == null || !contexts.isCurrent(playerId, opened)) return;
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
              render(page, player, orders, failure);
            }
          }, 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, List<Order> orders, Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    addMarketsNavigation(page, player);
    int slot = 9;
    for (Order order : orders) {
      if (slot >= 45) break;
      List<Component> lore = List.of(
          messages.component(player, "ui-order-status", order.status()),
          messages.component(player, "ui-order-remaining", order.remainingQuantity(),
              order.originalQuantity()),
          messages.component(player, "ui-order-price", order.limitPrice() == null
              ? order.slippageBoundary() : order.limitPrice()));
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
          .customName(messages.component(player, "ui-order-title", order.side(), order.marketId()))
          .lore(lore));
      icon.withActions(new RunnableAction(click -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()
            || !online.hasPermission("quickshop.exchange.use")
            || !online.hasPermission("quickshop.exchange.order.cancel")) {
          return;
        }
        contexts.put(playerId, ExchangeMenuRequest.cancel(UUID.randomUUID(), playerId, order.orderId()));
        MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.CANCEL_CONFIRM.page(),
            click.player());
      })).withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
  }

  private void addMarketsNavigation(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("COMPASS", 1)
        .customName(messages.component(player, "ui-nav-markets")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.MARKETS.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
              click.player());
        })).withSlot(0).build());
  }
}
