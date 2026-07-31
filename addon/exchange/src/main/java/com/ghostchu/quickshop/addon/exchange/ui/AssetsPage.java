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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Displays configured custody assets, balances and recent transfers for the current player. */
final class AssetsPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final AssetTransferPrompt prompts;
  private final ExchangeUiMessages messages;
  private final ExchangeMenuChrome chrome;

  AssetsPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
             AddonMessageService messages) {
    this(views, contexts, messages, ExchangeClockDisplay.disabled());
  }

  AssetsPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
             AddonMessageService messages, ExchangeClockDisplay clock) {
    this.views = views;
    this.contexts = contexts;
    this.prompts = new AssetTransferPrompt(contexts, UUID::randomUUID);
    this.messages = new ExchangeUiMessages(messages);
    this.chrome = new ExchangeMenuChrome(contexts, this.messages, clock);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    try {
      AssetPageSnapshot snapshot = AssetPageSnapshot.combine(
          views.accountAssets(playerId),
          views.accountTransfers(playerId, 12, 0)).join();
      render(page, player, snapshot, null);
    } catch (Exception failure) {
      render(page, player, null, failure);
    }
  }

  private void render(PlayerInstancePage page, Player player, AssetPageSnapshot snapshot,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null || snapshot == null || snapshot.failure() != null) {
      chrome.error(page, player, ExchangeMenuPage.ASSETS, "ui-guide-assets", "ui-data-unavailable");
      return;
    }
    chrome.prepare(page, player, ExchangeMenuPage.ASSETS, "ui-guide-assets");
    List<AssetPageRows.Row> assetRows = AssetPageRows.merge(views.transferTargets(), snapshot.assets());
    if (assetRows.isEmpty() && snapshot.transfers().isEmpty()) {
      chrome.empty(page, player, "ui-empty-assets", "ui-empty-assets-action");
      return;
    }
    if (!assetRows.isEmpty()) {
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("GOLD_BLOCK", messages.component(player, "ui-assets-section-balances")
              .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)))
          .withSlot(ExchangeMenuChrome.CONTENT_SLOTS.get(0)).build());
    }
    int index = 1;
    for (AssetPageRows.Row row : assetRows) {
      if (index >= 14) break;
      int slot = ExchangeMenuChrome.CONTENT_SLOTS.get(index++);
      TransferTarget target = row.target();
      List<Component> lore = List.of(
          messages.component(player, "ui-assets-available", row.available().toPlainString()),
          messages.component(player, "ui-assets-frozen", row.frozen().toPlainString()),
          messages.component(player, "ui-assets-deposit-action"),
          messages.component(player, "ui-assets-withdraw-action"));
      IconBuilder icon = new IconBuilder(ItemStackCompat.of(
          target.kind() == TransferTarget.Kind.CURRENCY ? "GOLD_INGOT" : "CHEST", Component.text(target.displayName())).lore(lore));
      icon.withActions(
          new RunnableAction(click -> requestTransfer(playerId, target, true), ActionType.LEFT_CLICK),
          new RunnableAction(click -> requestTransfer(playerId, target, false), ActionType.RIGHT_CLICK))
          .withSlot(slot);
      ExchangePageIcons.add(page, playerId, icon.build());
    }
    int transferStart = Math.max(index, 14);
    if (!snapshot.transfers().isEmpty() && transferStart < ExchangeMenuChrome.CONTENT_SLOTS.size()) {
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("MAP", messages.component(player, "ui-assets-section-transfers")
              .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)))
          .withSlot(ExchangeMenuChrome.CONTENT_SLOTS.get(transferStart)).build());
      transferStart++;
    }
    for (TransferRecord transfer : snapshot.transfers()) {
      if (transferStart >= ExchangeMenuChrome.CONTENT_SLOTS.size()) break;
      int slot = ExchangeMenuChrome.CONTENT_SLOTS.get(transferStart++);
      String reason = transfer.failureReason() == null ? "" : " " + transfer.failureReason();
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("HOPPER", messages.component(player, "ui-assets-transfer-title", transfer.status()))
          .lore(List.of(messages.component(player, "ui-assets-transfer-kind",
                  transfer.type(), transfer.assetId()),
              messages.component(player, "ui-assets-transfer-amount",
                  transfer.amount().toPlainString()),
              messages.component(player, "ui-assets-transfer-status",
                  transfer.status() + reason)))).withSlot(slot).build());
    }
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
