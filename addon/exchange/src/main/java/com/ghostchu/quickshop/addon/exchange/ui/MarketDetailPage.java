package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.menu.shared.GuiChatInputManager;
import java.util.List;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays a selected quote and exposes permission-gated limit and protected market entry. */
final class MarketDetailPage {
  private static final java.util.List<Duration> TIMEFRAMES = List.of(
      Duration.ofMinutes(9), Duration.ofMinutes(135), Duration.ofHours(9),
      Duration.ofHours(36));
  private static final java.util.List<String> TIMEFRAME_KEYS = List.of(
      "ui-trend-timeframe-1m", "ui-trend-timeframe-15m", "ui-trend-timeframe-1h",
      "ui-trend-timeframe-4h");

  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final OrderEntryPrompt prompts;
  private final OrderEntryAccess access;
  private final ExchangeUiMessages messages;
  private final MarketDashboardPresenter presenter = new MarketDashboardPresenter();
  private final java.util.Map<UUID, Duration> timeframes = new java.util.concurrent.ConcurrentHashMap<>();

  MarketDetailPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
                   RolloutPolicy rollout, AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.prompts = new OrderEntryPrompt(contexts, UUID::randomUUID);
    this.access = new OrderEntryAccess(rollout);
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest request = contexts.get(playerId).orElse(null);
    if (request == null || request.marketId() == null) {
      renderFailure(page, player, playerId, "ui-market-not-selected");
      return;
    }
    views.subscribeMarketUpdates(playerId, update -> {
      if (update.marketIds().contains(request.marketId()) && contexts.isCurrent(playerId, request)
          && player.isOnline()) {
        refresh(page, player, request);
      }
    });
    refresh(page, player, request);
  }

  private void refresh(PlayerInstancePage page, Player player, ExchangeMenuRequest request) {
    UUID playerId = player.getUniqueId();
    Duration window = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    views.marketDashboard(request.marketId(), window).whenComplete((dashboard, failure) -> {
      if (!ExchangePageRenderGuard.permits(contexts, playerId, request, player::isOnline)) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, request, player::isOnline)) {
              render(page, player, dashboard, failure);
            }
          }, 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, MarketDashboardSnapshot dashboard,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null) {
      renderFailure(page, player, playerId, "ui-data-unavailable");
      return;
    }
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    MarketRow row = dashboard.market();
    String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
    String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
    String spread = dashboard.spread() == null ? "-" : dashboard.spread().toPlainString();
    String spreadPercent = dashboard.spreadPercent() == null ? "-"
        : dashboard.spreadPercent().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros()
            .toPlainString() + "%";
    java.util.ArrayList<Component> lore = new java.util.ArrayList<>(List.of(
        messages.component(player, "ui-market-last", row.lastPrice().toPlainString()),
        messages.component(player, "ui-market-bid", bid),
        messages.component(player, "ui-market-ask", ask),
        messages.component(player, "ui-market-spread", spread, spreadPercent),
        messages.component(player, "ui-market-change-percent",
            row.change24h().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros()
                .toPlainString()),
        messages.component(player, "ui-market-notional", notional(dashboard)),
        messages.component(player, "ui-market-volatility",
            row.volatility24h() == null ? "-"
                : row.volatility24h().multiply(java.math.BigDecimal.valueOf(100))
                    .stripTrailingZeros().toPlainString() + "%"),
        messages.component(player, "ui-market-high-low",
            row.high24h() == null ? "-" : row.high24h().toPlainString(),
            row.low24h() == null ? "-" : row.low24h().toPlainString()),
        messages.component(player, "ui-market-volume", row.volume24h()),
        messages.component(player, "ui-market-status", row.status().name())));
    if (row.assetType() != null) {
      lore.add(messages.component(player, "ui-market-asset-type", row.assetType()));
    }
    if (row.symbol() != null) {
      lore.add(messages.component(player, "ui-market-symbol", row.symbol()));
    }
    if (row.totalSupply() != null) {
      lore.add(messages.component(player, "ui-market-total-supply", row.totalSupply()));
    }
    if (row.securityStatus() != null) {
      lore.add(messages.component(player, "ui-market-security-status", row.securityStatus()));
    }
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BOOK", 1)
        .customName(Component.text(row.displayName())).lore(lore)).withSlot(4).build());
    addNavigation(page, player, 0, "COMPASS", "ui-nav-markets", ExchangeMenuPage.MARKETS);
    addNavigation(page, player, 1, "CHEST", "ui-nav-assets", ExchangeMenuPage.ASSETS);
    addNavigation(page, player, 2, "WRITABLE_BOOK", "ui-nav-orders", ExchangeMenuPage.ORDERS);
    addTimeframeControl(page, player,
        contexts.get(playerId).orElse(null));
    Duration window = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    MarketDashboardPresenter.DashboardRows rows = presenter.present(dashboard, window);
    renderDepth(page, player, rows);
    renderCandles(page, player, rows);
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.LIMIT, "LIME_CONCRETE", 29,
        "ui-order-limit-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.LIMIT, "RED_CONCRETE", 33,
        "ui-order-limit-sell", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.MARKET, "GOLD_BLOCK", 38,
        "ui-order-market-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.MARKET, "ORANGE_CONCRETE", 42,
        "ui-order-market-sell", ActionType.LEFT_CLICK);
  }

  private void addTimeframeControl(PlayerInstancePage page, Player player,
                                   ExchangeMenuRequest request) {
    if (request == null) {
      return;
    }
    UUID playerId = player.getUniqueId();
    Duration current = timeframes.getOrDefault(playerId, TIMEFRAMES.getFirst());
    int index = Math.max(0, TIMEFRAMES.indexOf(current));
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("CLOCK", 1)
        .customName(messages.component(player, TIMEFRAME_KEYS.get(index))))
        .withActions(new RunnableAction(click -> {
          int next = (index + 1) % TIMEFRAMES.size();
          timeframes.put(playerId, TIMEFRAMES.get(next));
          refresh(page, player, request);
        })).withSlot(16).build());
  }

  private void renderDepth(PlayerInstancePage page, Player player,
                           MarketDashboardPresenter.DashboardRows rows) {
    for (int index = 0; index < rows.bids().size(); index++) {
      addDepthIcon(page, player, rows.bids().get(index), true, 9 + index);
      addDepthIcon(page, player, rows.asks().get(index), false, 14 + index);
    }
  }

  private void addDepthIcon(PlayerInstancePage page, Player player,
                            MarketDashboardPresenter.DepthRow row, boolean bid, int slot) {
    if (row.empty()) {
      page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(
          "BLACK_STAINED_GLASS_PANE", 1).customName(messages.component(player, "ui-depth-empty")))
          .withSlot(slot).build());
      return;
    }
    String material = row.executable() ? (bid ? "LIME_STAINED_GLASS_PANE" : "RED_STAINED_GLASS_PANE")
        : "GRAY_STAINED_GLASS_PANE";
    List<Component> lore = List.of(
        messages.component(player, "ui-depth-price", row.price().toPlainString()),
        messages.component(player, "ui-depth-quantity", row.quantity()),
        messages.component(player, "ui-depth-cumulative", row.cumulativeQuantity()),
        messages.component(player, row.executable() ? "ui-depth-executable" : "ui-depth-protected"));
    page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material,
        Math.max(1, row.strength())).customName(messages.component(player,
            bid ? "ui-depth-bid" : "ui-depth-ask")).lore(lore)).withSlot(slot).build());
  }

  private void renderCandles(PlayerInstancePage page, Player player,
                             MarketDashboardPresenter.DashboardRows rows) {
    for (int index = 0; index < rows.candles().size(); index++) {
      MarketDashboardPresenter.CandleRow row = rows.candles().get(index);
      int slot = 19 + index;
      if (row.empty()) {
        page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(
            "GRAY_STAINED_GLASS_PANE", 1)
            .customName(messages.component(player, "ui-trend-empty"))).withSlot(slot).build());
        continue;
      }
      String material = switch (row.direction()) {
        case UP -> "LIME_STAINED_GLASS_PANE";
        case DOWN -> "RED_STAINED_GLASS_PANE";
        case FLAT -> "YELLOW_STAINED_GLASS_PANE";
      };
      var candle = row.candle();
      List<Component> lore = List.of(
          messages.component(player, "ui-trend-time", candle.bucketStart().toString()),
          messages.component(player, "ui-trend-open", candle.open().toPlainString()),
          messages.component(player, "ui-trend-high", candle.high().toPlainString()),
          messages.component(player, "ui-trend-low", candle.low().toPlainString()),
          messages.component(player, "ui-trend-close", candle.close().toPlainString()),
          messages.component(player, "ui-trend-volume", candle.volume()));
      page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material,
          Math.max(1, row.strength())).customName(messages.component(player,
              "ui-trend-title", messages.text(player, directionKey(row.direction())))).lore(lore))
          .withSlot(slot).build());
    }
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

  private static String notional(MarketDashboardSnapshot dashboard) {
    return dashboard.notional24h() == null ? "-" : dashboard.notional24h().toPlainString();
  }

  private static String directionKey(MarketDashboardPresenter.CandleDirection direction) {
    return switch (direction) {
      case UP -> "ui-trend-up";
      case DOWN -> "ui-trend-down";
      case FLAT -> "ui-trend-flat";
    };
  }

  private void addOrderIcon(PlayerInstancePage page, Player player, MarketRow row,
                            OrderSide side, OrderType type, String material, int slot,
                            String title, ActionType actionType) {
    List<Component> lore = type == OrderType.LIMIT
        ? List.of(messages.component(player, "ui-order-limit-format"),
            messages.component(player, "ui-order-limit-example"))
        : List.of(messages.component(player, "ui-order-market-format"),
            messages.component(player, "ui-order-market-fixed-boundary"));
    page.addIcon(player.getUniqueId(), new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(messages.component(player, title)).lore(lore))
        .withActions(new RunnableAction(click -> requestOrder(player, row, side, type), actionType))
        .withSlot(slot).build());
  }

  private void requestOrder(Player player, MarketRow row, OrderSide side, OrderType type) {
    String denial = access.denial(player.getUniqueId(), row.status(), type, player::hasPermission)
        .orElse(null);
    if (denial != null) {
      player.sendMessage(messages.component(player, denial));
      return;
    }
    Function<String, Boolean> handler = type == OrderType.LIMIT
        ? prompts.limit(player.getUniqueId(), row.marketId(), side,
            ignored -> player.sendMessage(messages.component(player, "ui-order-limit-invalid")))
        : prompts.market(player.getUniqueId(), row.marketId(), side,
            ignored -> player.sendMessage(messages.component(player, "ui-order-market-invalid")));
    String prompt = type == OrderType.LIMIT
        ? messages.text(player, "ui-order-limit-prompt")
        : messages.text(player, "ui-order-market-prompt");
    GuiChatInputManager.getInstance().requestInput(player, handler, prompt, ExchangeMenu.NAME,
        ExchangeMenuPage.ORDER_CONFIRM.page());
    player.closeInventory();
  }

  private void renderFailure(PlayerInstancePage page, Player player, UUID playerId, String key) {
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    Component title = player == null ? Component.text(key) : messages.component(player, key);
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
        .customName(title)).withSlot(22).build());
  }
}
