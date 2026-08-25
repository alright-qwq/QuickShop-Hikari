package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.List;
import java.util.UUID;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Renders market summaries using locale-aware player text. */
final class MarketListPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;

  MarketListPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
                 AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null) return;
    views.subscribeMarketUpdates(playerId, update -> {
      if (contexts.isCurrent(playerId, opened) && player.isOnline()) {
        refresh(page, player, opened);
      }
    });
    refresh(page, player, opened);
  }

  private void refresh(PlayerInstancePage page, Player player, ExchangeMenuRequest opened) {
    UUID playerId = player.getUniqueId();
    views.marketList().whenComplete((snapshot, failure) -> {
      if (!ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
              render(page, player, snapshot, failure);
            }
          }, 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, MarketListSnapshot snapshot,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    addOverview(page, player, snapshot.overview());
    addNavigation(page, player, 0, "CHEST", "ui-nav-assets", ExchangeMenuPage.ASSETS);
    addNavigation(page, player, 8, "WRITABLE_BOOK", "ui-nav-orders", ExchangeMenuPage.ORDERS);
    int slot = 9;
    for (MarketRow row : snapshot.markets()) {
      if (slot >= 45) break;
      String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
      String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
          .customName(net.kyori.adventure.text.Component.text(row.displayName()))
          .lore(List.of(
              messages.component(player, "ui-market-last", row.lastPrice().toPlainString()),
              messages.component(player, "ui-market-bid-ask", bid, ask),
              messages.component(player, "ui-market-volume", row.volume24h()),
              messages.component(player, "ui-market-status", row.status().name()))))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.market(row.marketId()));
            MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_DETAIL.page(),
                click.player());
          })).withSlot(slot++).build());
    }
  }

  private void addOverview(PlayerInstancePage page, Player player, MarketOverviewSnapshot overview) {
    String active = overview.mostActive() == null ? "-" : overview.mostActive().displayName();
    String gainer = overview.biggestGainer() == null ? "-" : overview.biggestGainer().displayName();
    String loser = overview.biggestLoser() == null ? "-" : overview.biggestLoser().displayName();
    page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of("MAP", 1)
        .customName(messages.component(player, "ui-overview-title"))
        .lore(List.of(
            messages.component(player, "ui-overview-markets", overview.marketCount()),
            messages.component(player, "ui-overview-breadth", overview.risingCount(),
                overview.fallingCount()),
            messages.component(player, "ui-overview-volume", overview.totalVolume24h()),
            messages.component(player, "ui-overview-notional",
                overview.totalNotional24h().toPlainString()),
            messages.component(player, "ui-overview-active", active),
            messages.component(player, "ui-overview-gainer", gainer),
            messages.component(player, "ui-overview-loser", loser)))).withSlot(4).build());
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String title, ExchangeMenuPage target) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(messages.component(player, title)))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(target.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, target.page(), click.player());
        })).withSlot(slot).build());
  }
}
