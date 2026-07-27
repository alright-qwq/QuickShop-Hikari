package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import net.kyori.adventure.text.Component;
import java.util.List;
import java.util.UUID;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Renders the market summary using only values supplied by {@link ExchangeViewService}. */
final class MarketListPage {
  private final ExchangeViewService views;

  MarketListPage(ExchangeViewService views) {
    this.views = views;
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) {
      return;
    }
    UUID playerId = callback.getPlayer().identifier();
    views.marketRows().whenComplete((rows, failure) -> {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) {
        return;
      }
      QuickShop.folia().getScheduler().runAtEntityLater(player, () -> render(page, playerId, rows, failure), 1L);
    });
  }

  private static void render(PlayerInstancePage page, UUID playerId, List<MarketRow> rows,
                             Throwable failure) {
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(Component.text("Exchange data unavailable"))).withSlot(22).build());
      return;
    }
    int slot = 9;
    for (MarketRow row : rows) {
      if (slot >= 45) {
        break;
      }
      String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
      String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
          .customName(Component.text(row.displayName()))
          .lore(List.of(Component.text("Last: " + row.lastPrice().toPlainString()),
              Component.text("Bid / Ask: " + bid + " / " + ask),
              Component.text("24h Volume: " + row.volume24h()),
              Component.text("Status: " + row.status().name()))))
          .withSlot(slot++).build());
    }
  }
}
