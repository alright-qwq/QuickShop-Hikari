package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.math.BigDecimal;
import java.util.ArrayList;
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

/** Renders market summaries using locale-aware player text. */
final class MarketListPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;
  private final java.util.Map<UUID, MarketListSnapshot.SortMode> sortModes =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Map<UUID, AssetFilter> assetFilters =
      new java.util.concurrent.ConcurrentHashMap<>();

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
    addFilterControl(page, player);
    addSortControl(page, player);
    addNavigation(page, player, 0, "CHEST", "ui-nav-assets", ExchangeMenuPage.ASSETS);
    addNavigation(page, player, 8, "WRITABLE_BOOK", "ui-nav-orders", ExchangeMenuPage.ORDERS);
    int slot = 9;
    List<MarketRow> filtered = MarketListSnapshot.filtered(snapshot.markets(),
        assetFilters.getOrDefault(playerId, AssetFilter.ALL).name());
    for (MarketRow row : MarketListSnapshot.sorted(filtered,
        sortModes.getOrDefault(playerId, MarketListSnapshot.SortMode.NOTIONAL))) {
      if (slot >= 45) break;
      String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
      String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
      List<Component> lore = new ArrayList<>(List.of(
          messages.component(player, "ui-market-last", row.lastPrice().toPlainString()),
          messages.component(player, "ui-market-bid-ask", bid, ask),
          messages.component(player, "ui-market-volume", row.volume24h()),
          messages.component(player, "ui-market-list-notional",
              row.notional24h() == null ? "-"
                  : row.notional24h().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()),
          messages.component(player, "ui-market-status", row.status().name())));
      if (row.volatility24h() != null) {
        lore.add(messages.component(player, "ui-market-volatility",
            percent(row.volatility24h())));
      }
      if (row.assetType() != null) {
        lore.add(messages.component(player, "ui-market-asset-type", row.assetType()));
      }
      if (row.symbol() != null) {
        lore.add(messages.component(player, "ui-market-symbol", row.symbol()));
      }
      if (row.totalSupply() != null) {
        lore.add(messages.component(player, "ui-market-total-supply", row.totalSupply()));
      }
      if (row.issuedSupply() != null) {
        lore.add(messages.component(player, "ui-market-issued-supply",
            row.issuedSupply(), row.totalSupply()));
      }
      lore.add(messages.component(player, "ui-market-change-percent",
          percent(row.change24h())));
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
          .customName(net.kyori.adventure.text.Component.text(row.displayName()))
          .lore(lore))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.market(row.marketId()));
            MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_DETAIL.page(),
                click.player());
          })).withSlot(slot++).build());
    }
  }

  private void addFilterControl(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    AssetFilter filter = assetFilters.getOrDefault(playerId, AssetFilter.ALL);
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of(
        filter == AssetFilter.SECURITY ? "PAPER"
            : filter == AssetFilter.ITEM ? "CHEST" : "HOPPER", 1)
        .customName(messages.component(player, "ui-filter-title", filter.name()))
        .lore(List.of(messages.component(player, "ui-filter-hint"))))
        .withActions(new RunnableAction(click -> {
          contexts.get(playerId).ifPresent(opened -> {
            assetFilters.put(playerId, filter.next());
            refresh(page, player, opened);
          });
        })).withSlot(6).build());
  }

  private enum AssetFilter {
    ALL, SECURITY, ITEM;

    AssetFilter next() {
      return switch (this) {
        case ALL -> SECURITY;
        case SECURITY -> ITEM;
        case ITEM -> ALL;
      };
    }
  }

  private void addSortControl(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    MarketListSnapshot.SortMode mode =
        sortModes.getOrDefault(playerId, MarketListSnapshot.SortMode.NOTIONAL);
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("HOPPER", 1)
        .customName(messages.component(player, "ui-sort-title", mode.name()))
        .lore(List.of(messages.component(player, "ui-sort-hint"))))
        .withActions(new RunnableAction(click -> {
          contexts.get(playerId).ifPresent(opened -> {
            sortModes.put(playerId, mode.next());
            refresh(page, player, opened);
          });
        })).withSlot(7).build());
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
                overview.totalNotional24h()
                    .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()),
            messages.component(player, "ui-overview-active", active),
            messages.component(player, "ui-overview-gainer", gainer),
            messages.component(player, "ui-overview-loser", loser)))).withSlot(4).build());
  }

  private static String percent(BigDecimal fraction) {
    return fraction == null ? "-"
        : fraction.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros()
            .toPlainString() + "%";
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
