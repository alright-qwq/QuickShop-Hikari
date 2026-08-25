package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Renders the exact request held for a confirmation or account page. */
final class RequestSummaryPage {
  private final ExchangeMenuPage expected;
  private final ExchangeMenuContextStore contexts;
  private final ExchangeRequestSubmitter submitter;
  private final RolloutPolicy rollout;
  private final ExchangeUiMessages messages;

  RequestSummaryPage(ExchangeMenuPage expected, ExchangeMenuContextStore contexts,
                     ExchangeRequestSubmitter submitter, AddonMessageService messages) {
    this(expected, contexts, submitter, RolloutPolicy.DISABLED, messages);
  }

  RequestSummaryPage(ExchangeMenuPage expected, ExchangeMenuContextStore contexts,
                     ExchangeRequestSubmitter submitter, RolloutPolicy rollout,
                     AddonMessageService messages) {
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
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    ExchangeMenuRequest request = contexts.get(playerId).orElse(null);
    if (request == null || !expected.menuName().equals(request.menuName())) {
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-confirm-not-selected")))
          .withSlot(22);
      page.addIcon(playerId, icon.build());
      return;
    }
    List<Component> lore = summary(player, request);
    IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("PAPER", 1)
        .customName(messages.component(player, titleKey(request), titleArgument(request)))
        .lore(lore)).withSlot(22);
    page.addIcon(playerId, icon.build());
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

  private List<Component> summary(Player player, ExchangeMenuRequest request) {
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
      }
      if (order.slippageBoundary() != null) {
        lines.add(messages.component(player, "ui-confirm-protection",
            order.slippageBoundary().toPlainString()));
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
