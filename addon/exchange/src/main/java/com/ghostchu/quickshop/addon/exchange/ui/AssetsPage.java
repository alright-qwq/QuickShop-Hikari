package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.menu.shared.GuiChatInputManager;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.ActionType;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays configured custody assets, balances and recent transfers for the current player. */
final class AssetsPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final AssetTransferPrompt prompts;
  private final ExchangeUiMessages messages;

  AssetsPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
             AddonMessageService messages) {
    this.views = views;
    this.contexts = contexts;
    this.prompts = new AssetTransferPrompt(contexts, UUID::randomUUID);
    this.messages = new ExchangeUiMessages(messages);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    AssetPageSnapshot.combine(views.accountAssets(playerId),
        views.accountTransfers(playerId, 12, 0)).whenComplete((snapshot, failure) -> {
      if (opened == null || !contexts.isCurrent(playerId, opened)) return;
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) return;
      QuickShop.folia().getScheduler().runAtEntityLater(player,
          () -> {
            if (ExchangePageRenderGuard.permits(contexts, playerId, opened, player::isOnline)) {
              render(page, player, snapshot, failure);
            }
          }, 1L);
    });
  }

  private void render(PlayerInstancePage page, Player player, AssetPageSnapshot snapshot,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (failure != null || snapshot == null || snapshot.failure() != null) {
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("BARRIER", 1)
          .customName(messages.component(player, "ui-data-unavailable"))).withSlot(22).build());
      return;
    }
    addMarketsNavigation(page, player);
    int slot = 9;
    AssetPageRows.Merged merged = AssetPageRows.merge(views.transferTargets(), snapshot.assets());
    addTotalValue(page, player, playerId, merged);
    for (AssetPageRows.Row row : merged.rows()) {
      if (slot >= 45) break;
      TransferTarget target = row.target();
      List<Component> lore = List.of(
          messages.component(player, "ui-assets-available", row.available().toPlainString()),
          messages.component(player, "ui-assets-frozen", row.frozen().toPlainString()),
          messages.component(player, "ui-assets-deposit-action"),
          messages.component(player, "ui-assets-withdraw-action"));
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of(
          target.kind() == TransferTarget.Kind.CURRENCY ? "GOLD_INGOT" : "CHEST", 1)
          .customName(Component.text(target.displayName())).lore(lore));
      icon.withActions(
          new RunnableAction(click -> requestTransfer(playerId, target, true), ActionType.LEFT_CLICK),
          new RunnableAction(click -> requestTransfer(playerId, target, false), ActionType.RIGHT_CLICK))
          .withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
    for (AssetPageRows.SecurityRow security : merged.securities()) {
      if (slot >= 45) break;
      java.util.ArrayList<Component> securityLore = new java.util.ArrayList<>(List.of(
          messages.component(player, "ui-assets-virtual-security"),
          messages.component(player, "ui-assets-symbol", security.symbol()),
          messages.component(player, "ui-assets-available", security.available().toPlainString()),
          messages.component(player, "ui-assets-frozen", security.frozen().toPlainString()),
          messages.component(player, "ui-assets-open-market")));
      java.math.BigDecimal marketValue = marketValue(security);
      if (marketValue != null) {
        securityLore.add(messages.component(player, "ui-assets-market-value",
            marketValue.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
      }
      IconBuilder icon = new IconBuilder(QuickShop.getInstance().stack().of("EMERALD", 1)
          .customName(Component.text(security.displayName())).lore(securityLore));
      icon.withActions(new RunnableAction(click -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()) return;
        contexts.put(playerId, ExchangeMenuRequest.market(security.marketId()));
        MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKET_DETAIL.page(),
            click.player());
      })).withSlot(slot++);
      page.addIcon(playerId, icon.build());
    }
    for (TransferRecord transfer : snapshot.transfers()) {
      if (slot >= 45) break;
      String reason = transfer.failureReason() == null ? "" : " " + transfer.failureReason();
      java.util.List<Component> transferLore = List.of(
          messages.component(player, "ui-assets-transfer-kind",
              transfer.type(), transfer.assetId()),
          messages.component(player, "ui-assets-transfer-amount",
              transfer.amount().toPlainString()),
          messages.component(player, "ui-assets-transfer-status",
              transfer.status() + reason),
          messages.component(player, "ui-history-created-at",
              messages.relativeTime(transfer.updatedAt())));
      page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("HOPPER", 1)
          .customName(messages.component(player, "ui-assets-transfer-title", transfer.status()))
          .lore(transferLore)).withSlot(slot++).build());
    }
  }

  private void addTotalValue(PlayerInstancePage page, Player player, UUID playerId,
                             AssetPageRows.Merged merged) {
    java.math.BigDecimal total = java.math.BigDecimal.ZERO;
    for (AssetPageRows.Row row : merged.rows()) {
      if (row.target().kind() == TransferTarget.Kind.CURRENCY) {
        total = total.add(row.available()).add(row.frozen());
      }
    }
    for (AssetPageRows.SecurityRow security : merged.securities()) {
      java.math.BigDecimal value = marketValue(security);
      if (value != null) {
        total = total.add(value);
      }
    }
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("DIAMOND", 1)
        .customName(messages.component(player, "ui-assets-total-value"))
        .lore(List.of(messages.component(player, "ui-assets-total-value-amount",
            total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())))).withSlot(4).build());
  }

  private java.math.BigDecimal marketValue(AssetPageRows.SecurityRow security) {
    String marketId = security.marketId();
    if (marketId == null) {
      return null;
    }
    com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote quote = views.marketQuote(marketId);
    if (quote == null || quote.lastPrice() == null) {
      return null;
    }
    java.math.BigDecimal quantity = security.available().add(security.frozen());
    return quote.lastPrice().multiply(quantity);
  }

  private void addMarketsNavigation(PlayerInstancePage page, Player player) {
    UUID playerId = player.getUniqueId();
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of("COMPASS", 1)
        .customName(messages.component(player, "ui-nav-markets")))
        .withActions(new RunnableAction(click -> {
          contexts.put(playerId, ExchangeMenuRequest.page(ExchangeMenuPage.MARKETS.menuName()));
          MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
              click.player());
        })).withSlot(0).build());
  }

  private void requestTransfer(UUID playerId, TransferTarget target, boolean deposit) {
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    String permission = deposit ? "quickshop.exchange.deposit" : "quickshop.exchange.withdraw";
    if (!player.hasPermission(permission)) {
      player.sendMessage(messages.component(player, "permission-denied"));
      return;
    }
    ExchangeMenuRequest.TransferKind kind = kind(target, deposit);
    java.util.function.Function<String, Boolean> handler =
        target.kind() == TransferTarget.Kind.CURRENCY
            ? prompts.currency(playerId, kind, target.assetId(),
                ignored -> player.sendMessage(messages.component(player, "ui-transfer-money-invalid")))
            : prompts.item(playerId, kind, target.marketId(),
                ignored -> player.sendMessage(messages.component(player, "ui-transfer-item-invalid")));
    String prompt = target.kind() == TransferTarget.Kind.CURRENCY
        ? messages.text(player, "ui-transfer-money-prompt")
        : messages.text(player, "ui-transfer-item-prompt");
    GuiChatInputManager.getInstance().requestInput(player, handler, prompt, ExchangeMenu.NAME,
        ExchangeMenuPage.TRANSFER_CONFIRM.page());
    player.closeInventory();
  }

  private static ExchangeMenuRequest.TransferKind kind(TransferTarget target, boolean deposit) {
    if (target.kind() == TransferTarget.Kind.CURRENCY) {
      return deposit ? ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT
          : ExchangeMenuRequest.TransferKind.MONEY_WITHDRAWAL;
    }
    return deposit ? ExchangeMenuRequest.TransferKind.ITEM_DEPOSIT
        : ExchangeMenuRequest.TransferKind.ITEM_WITHDRAWAL;
  }
}
