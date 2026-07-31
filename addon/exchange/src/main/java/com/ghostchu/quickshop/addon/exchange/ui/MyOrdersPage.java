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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays the first bounded page of a player's currently cancellable orders. */
final class MyOrdersPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;
  private final ExchangeMenuChrome chrome;

  MyOrdersPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
               AddonMessageService messages) {
    this(views, contexts, messages, ExchangeClockDisplay.disabled());
  }

  MyOrdersPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
               AddonMessageService messages, ExchangeClockDisplay clock) {
    this.views = views;
    this.contexts = contexts;
    this.messages = new ExchangeUiMessages(messages);
    this.chrome = new ExchangeMenuChrome(contexts, this.messages, clock);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    try {
      List<Order> orders = views.accountOrders(playerId, 28, 0).join();
      render(page, player, orders, null);
    } catch (Exception failure) {
      render(page, player, null, failure);
    }
  }

  private void render(PlayerInstancePage page, Player player, List<Order> orders, Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null) {
      chrome.error(page, player, ExchangeMenuPage.ORDERS, "ui-guide-orders", "ui-data-unavailable");
      return;
    }
    chrome.prepare(page, player, ExchangeMenuPage.ORDERS, "ui-guide-orders");
    if (orders.isEmpty()) {
      chrome.empty(page, player, "ui-empty-orders", "ui-empty-orders-action");
      return;
    }
    int index = 0;
    for (Order order : orders) {
      if (index >= ExchangeMenuChrome.CONTENT_SLOTS.size()) break;
      int slot = ExchangeMenuChrome.CONTENT_SLOTS.get(index++);
      List<Component> lore = List.of(
          messages.component(player, "ui-order-status", order.status()),
          messages.component(player, "ui-order-remaining", order.remainingQuantity(),
              order.originalQuantity()),
          messages.component(player, "ui-order-filled",
              order.originalQuantity() - order.remainingQuantity(), order.originalQuantity()),
          messages.component(player, "ui-order-price", order.limitPrice() == null
              ? order.slippageBoundary() : order.limitPrice()));
      IconBuilder icon = new IconBuilder(ItemStackCompat.of("PAPER", messages.component(player, "ui-order-title", order.side(), order.marketId()))
          .lore(lore));
      icon.withActions(new RunnableAction(click -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()
            || !online.hasPermission("quickshop.exchange.use")
            || !online.hasPermission("quickshop.exchange.order.cancel")) {
          return;
        }
        contexts.put(playerId, ExchangeMenuRequest.cancel(UUID.randomUUID(), playerId, order.orderId()));
        ExchangeMenuNavigator.open(click.player(), ExchangeMenuPage.CANCEL_CONFIRM);
      })).withSlot(slot);
      ExchangePageIcons.add(page, playerId, icon.build());
    }
  }
}
