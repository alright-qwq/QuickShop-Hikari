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
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    views.marketRows().whenComplete((rows, failure) -> {
      if (opened == null || !contexts.isCurrent(playerId, opened)) return;
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> render(page, player, rows, failure), 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, List<MarketRow> rows,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    int slot = 9;
    for (MarketRow row : rows) {
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
}
