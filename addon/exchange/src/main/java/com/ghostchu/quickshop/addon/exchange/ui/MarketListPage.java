package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Renders market summaries using locale-aware player text. */
final class MarketListPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;
  private final ExchangeMenuChrome chrome;

  MarketListPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
                 AddonMessageService messages) {
    this(views, contexts, messages, ExchangeClockDisplay.disabled());
  }

  MarketListPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
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
      List<MarketRow> rows = views.marketRows().join();
      render(page, player, rows, null);
    } catch (Exception failure) {
      org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("qssuite-exchange");
      if (plugin != null) {
        plugin.getLogger().warning("Exchange MarketListPage load failed: " + failure);
        failure.printStackTrace();
      }
      render(page, player, null, failure);
    }
  }

  private void render(PlayerInstancePage page, Player player, List<MarketRow> rows,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null) {
      chrome.error(page, player, ExchangeMenuPage.MARKETS, "ui-guide-markets", "ui-data-unavailable");
      return;
    }
    chrome.prepare(page, player, ExchangeMenuPage.MARKETS, "ui-guide-markets");
    if (rows.isEmpty()) {
      chrome.empty(page, player, "ui-empty-markets", "ui-empty-markets-action");
      return;
    }
    int index = 0;
    for (MarketRow row : rows) {
      if (index >= ExchangeMenuChrome.CONTENT_SLOTS.size()) break;
      int slot = ExchangeMenuChrome.CONTENT_SLOTS.get(index++);
      String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
      String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
      String material = row.status() == com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus.OPEN
          ? "EMERALD" : "REDSTONE";
      String actionKey = row.status() == com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus.OPEN
          ? "ui-market-open-action" : "ui-market-closed-action";
      String changeKey = row.change24h().signum() >= 0 ? "ui-market-change-up" : "ui-market-change-down";
      String changeLabelKey = row.change24h().signum() >= 0
          ? "ui-market-change-up-label" : "ui-market-change-down-label";
      NamedTextColor changeColor = row.change24h().signum() >= 0
          ? NamedTextColor.GREEN : NamedTextColor.RED;
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of(material, Component.text(row.displayName()))
          .lore(List.of(
              messages.component(player, "ui-market-last", row.lastPrice().toPlainString()),
              messages.component(player, changeKey, row.change24h().toPlainString()),
              messages.component(player, "ui-market-bid-ask", bid, ask),
              messages.component(player, "ui-market-volume", row.volume24h()),
              messages.component(player, "ui-market-status", row.status().name()),
              messages.component(player, actionKey))))
          .withActions(new RunnableAction(click -> {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin("qssuite-exchange");
            if (plugin != null) {
              plugin.getLogger().info("Market icon clicked! marketId=" + row.marketId());
            }
            contexts.put(playerId, ExchangeMenuRequest.market(row.marketId()));
            ExchangeMenuNavigator.open(click.player(), ExchangeMenuPage.MARKET_DETAIL);
          })).withSlot(slot).build());
    }
  }
}
