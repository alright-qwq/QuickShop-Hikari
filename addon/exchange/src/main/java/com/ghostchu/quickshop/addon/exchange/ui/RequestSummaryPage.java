package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
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

/** Renders the exact request held for a confirmation or account page. */
final class RequestSummaryPage {
  private final ExchangeViewService views;
  private final ExchangeMenuPage expected;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeRequestSubmitter submitter;
  private final RolloutPolicy rollout;
  private final ExchangeUiMessages messages;

  RequestSummaryPage(ExchangeMenuPage expected, ExchangeMenuContextStore contexts,
                     ExchangeRequestSubmitter submitter, AddonMessageService messages) {
    this(null, expected, contexts, submitter, RolloutPolicy.DISABLED, messages);
  }

  RequestSummaryPage(ExchangeViewService views, ExchangeMenuPage expected,
                     ExchangeMenuContextStore contexts, ExchangeRequestSubmitter submitter,
                     RolloutPolicy rollout, AddonMessageService messages) {
    this.views = views;
    this.expected = expected;
    this.contexts = contexts;
    this.submitter = submitter;
    this.rollout = rollout;
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    ExchangeMenuRequest request = contexts.get(playerId).orElse(null);
    if (request == null || !expected.menuName().equals(request.menuName())) {
      page.getIcons(playerId).clear();
      page.setLockEmptySlots(true);
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-confirm-not-selected")))
          .withSlot(22);
      page.addIcon(playerId, icon.build());
      return;
    }
    render(page, player, request, null);
    if (request.order() != null && views != null) {
      views.marketQuoteAsync(request.marketId())
          .whenComplete((quote, failure) -> {
            if (failure != null || !contexts.isCurrent(playerId, request)) return;
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) return;
            QuickShop.folia().getScheduler().runAtEntityLater(online,
                () -> {
                  if (ExchangePageRenderGuard.permits(contexts, playerId, request, online::isOnline)) {
                    render(page, online, request, quote);
                  }
                }, 1L);
          });
    }
  }

  private void render(PlayerInstancePage page, Player player, ExchangeMenuRequest request,
                      com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    List<Component> lore = summary(player, request, quote);
    IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
        .customName(messages.component(player, titleKey(request), titleArgument(request)))
        .lore(lore)).withSlot(22);
    page.addIcon(playerId, icon.build());
    if (request.order() != null && request.marketId() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("COMPASS", 1)
              .customName(messages.component(player, "ui-confirm-back-market")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.market(request.marketId()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.MARKET_DETAIL.page(), click.player());
          })).withSlot(0).build());
    } else if (request.transfer() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("CHEST", 1)
              .customName(messages.component(player, "ui-nav-assets")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(
                ExchangeMenuPage.ASSETS.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.ASSETS.page(), click.player());
          })).withSlot(0).build());
    } else if (request.orderId() != null) {
      page.addIcon(playerId, new IconBuilder(
          QuickShop.getInstance().stack().of("WRITABLE_BOOK", 1)
              .customName(messages.component(player, "ui-nav-orders")))
          .withActions(new RunnableAction(click -> {
            contexts.put(playerId, ExchangeMenuRequest.page(
                ExchangeMenuPage.ORDERS.menuName()));
            MenuManager.instance().open(ExchangeMenu.NAME,
                ExchangeMenuPage.ORDERS.page(), click.player());
          })).withSlot(0).build());
    }
    if (submitter != null && request.requestId() != null
        && (request.order() != null || request.orderId() != null || request.transfer() != null)) {
      IconBuilder confirm = new IconBuilder(QuickShop.getInstance().stack().of("LIME_CONCRETE", 1)
          .customName(messages.component(player, "ui-confirm-action")));
      confirm.withActions(new RunnableAction(click -> submit(request, playerId))).withSlot(31);
      page.addIcon(playerId, confirm.build());
    }
  }

  private static String titleKey(ExchangeMenuRequest request) {
    if (request.order() != null) return "ui-confirm-order-title";
    if (request.transfer() != null) return "ui-confirm-transfer-title";
    if (request.orderId() != null) return "ui-confirm-cancel-title";
    return "ui-confirm-title";
  }

  private static Object titleArgument(ExchangeMenuRequest request) {
    if (request.order() != null) return request.order().type();
    if (request.transfer() != null) return request.transfer().kind();
    return "";
  }

  private List<Component> summary(Player player, ExchangeMenuRequest request,
                                  com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote) {
    List<Component> lines = new ArrayList<>();
    if (request.requestId() != null) {
      lines.add(messages.component(player, "ui-confirm-request", request.requestId()));
    }
    if (request.marketId() != null) {
      lines.add(messages.component(player, "ui-confirm-market", request.marketId()));
    }
    if (request.order() != null) {
      var order = request.order();
      lines.add(messages.component(player, "ui-confirm-side", order.side()));
      lines.add(messages.component(player, "ui-confirm-quantity", order.quantity()));
      if (order.price() != null) {
        lines.add(messages.component(player, "ui-confirm-price", order.price().toPlainString()));
        lines.add(messages.component(player, "ui-confirm-estimated-notional",
            OrderConfirmation.estimatedNotional(order.price(), order.quantity()).toPlainString()));
        if (quote != null) {
          java.math.BigDecimal executable = order.side() == OrderSide.BUY
              ? quote.bestAsk() : quote.bestBid();
          if (executable != null) {
            lines.add(messages.component(player, "ui-confirm-current-quote",
                executable.toPlainString()));
          }
        }
      }
      if (order.slippageBoundary() != null) {
        lines.add(messages.component(player, "ui-confirm-protection",
            order.slippageBoundary().toPlainString()));
        lines.add(messages.component(player, "ui-confirm-estimated-notional",
            OrderConfirmation.estimatedNotional(order.slippageBoundary(), order.quantity())
                .toPlainString()));
        if (quote != null) {
          java.math.BigDecimal executable = order.side() == OrderSide.BUY
              ? quote.bestAsk() : quote.bestBid();
          if (executable != null) {
            lines.add(messages.component(player, "ui-confirm-current-quote",
                executable.toPlainString()));
          }
        }
      }
    }
    if (request.transfer() != null) {
      var transfer = request.transfer();
      lines.add(messages.component(player, "ui-confirm-asset", transfer.assetId()));
      if (transfer.amount() != null) {
        lines.add(messages.component(player, "ui-confirm-amount", transfer.amount()));
      }
      if (transfer.quantity() > 0) {
        lines.add(messages.component(player, "ui-confirm-quantity", transfer.quantity()));
      }
    }
    if (request.orderId() != null) {
      lines.add(messages.component(player, "ui-confirm-order", request.orderId()));
    }
    return List.copyOf(lines);
  }

  private void submit(ExchangeMenuRequest request, UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()
        || !ExchangeRequestPermission.allows(playerId, request, player::hasPermission, rollout)) {
      return;
    }
    if (!contexts.claim(playerId, request)) {
      return;
    }
    submitter.submit(request).whenComplete((result, failure) -> {
      Player onlinePlayer = Bukkit.getPlayer(playerId);
      if (onlinePlayer == null || !onlinePlayer.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(onlinePlayer, () -> {
        if (failure != null) {
          onlinePlayer.sendMessage(messages.component(onlinePlayer, "ui-confirm-submit-failed"));
        } else {
          onlinePlayer.sendMessage(messages.component(onlinePlayer, "ui-confirm-submit-result",
              result.outcome(), result.reference()));
        }
      }, 1L);
    });
  }
}
