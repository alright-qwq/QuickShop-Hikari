package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.menu.shared.GuiChatInputManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays a selected quote and exposes permission-gated limit and protected market entry. */
final class MarketDetailPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final OrderEntryPrompt prompts;
  private final OrderEntryAccess access;
  private final ExchangeUiMessages messages;

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
    views.marketRow(request.marketId()).whenComplete((row, failure) -> {
      if (!contexts.isCurrent(playerId, request) || !player.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> render(page, player, row, failure), 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, MarketRow row, Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null) {
      renderFailure(page, player, playerId, "ui-data-unavailable");
      return;
    }
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    String bid = row.bestBid() == null ? "-" : row.bestBid().toPlainString();
    String ask = row.bestAsk() == null ? "-" : row.bestAsk().toPlainString();
    List<Component> lore = List.of(
        messages.component(player, "ui-market-last", row.lastPrice().toPlainString()),
        messages.component(player, "ui-market-bid", bid),
        messages.component(player, "ui-market-ask", ask),
        messages.component(player, "ui-market-change", row.change24h().toPlainString()),
        messages.component(player, "ui-market-volume", row.volume24h()),
        messages.component(player, "ui-market-status", row.status().name()));
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BOOK", 1)
        .customName(Component.text(row.displayName())).lore(lore)).withSlot(22).build());
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.LIMIT, "LIME_CONCRETE", 29,
        "ui-order-limit-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.LIMIT, "RED_CONCRETE", 33,
        "ui-order-limit-sell", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.BUY, OrderType.MARKET, "GOLD_BLOCK", 38,
        "ui-order-market-buy", ActionType.LEFT_CLICK);
    addOrderIcon(page, player, row, OrderSide.SELL, OrderType.MARKET, "ORANGE_CONCRETE", 42,
        "ui-order-market-sell", ActionType.LEFT_CLICK);
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
