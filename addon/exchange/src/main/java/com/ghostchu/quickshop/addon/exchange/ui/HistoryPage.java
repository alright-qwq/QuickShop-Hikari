package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Shows bounded account-filtered trade, transfer and liability-ledger history. */
final class HistoryPage {
  private final ExchangeViewService views;
  private final ExchangeMenuContextStore contexts;
  private final AddonMessageService messages;
  private final ExchangeUiMessages uiMessages;
  private final ExchangeMenuChrome chrome;

  HistoryPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
              AddonMessageService messages) {
    this(views, contexts, messages, ExchangeClockDisplay.disabled());
  }

  HistoryPage(ExchangeViewService views, ExchangeMenuContextStore contexts,
              AddonMessageService messages, ExchangeClockDisplay clock) {
    this.views = views;
    this.contexts = contexts;
    this.messages = messages;
    this.uiMessages = new ExchangeUiMessages(messages);
    this.chrome = new ExchangeMenuChrome(contexts, uiMessages, clock);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    chrome.loading(page, player, ExchangeMenuPage.HISTORY, "ui-guide-history");
    int offset = HistoryPageSnapshot.offset(opened.page());
    try {
      HistoryPageSnapshot snapshot = HistoryPageSnapshot.combine(
          views.accountTrades(playerId, HistoryPageSnapshot.SECTION_SIZE, offset),
          views.accountTransfers(playerId, HistoryPageSnapshot.SECTION_SIZE, offset),
          views.accountLedger(playerId, HistoryPageSnapshot.SECTION_SIZE, offset)).join();
      render(page, player, snapshot, null);
    } catch (Exception failure) {
      render(page, player, null, failure);
    }
  }

  private void render(PlayerInstancePage page, Player player, HistoryPageSnapshot snapshot,
                      Throwable failure) {
    UUID playerId = player.getUniqueId();
    if (failure != null || snapshot == null || snapshot.failure() != null) {
      chrome.error(page, player, ExchangeMenuPage.HISTORY, "ui-guide-history", "ui-data-unavailable");
      return;
    }
    chrome.prepare(page, player, ExchangeMenuPage.HISTORY, "ui-guide-history");
    if (snapshot.trades().isEmpty() && snapshot.transfers().isEmpty() && snapshot.ledger().isEmpty()) {
      chrome.empty(page, player, "ui-empty-history", "ui-empty-history-action");
      return;
    }
    int slot = 10;
    for (Trade trade : snapshot.trades()) {
      if (slot >= 17) break;
      List<Component> lore = List.of(
          text(player, "ui-history-trade-quantity", trade.quantity()),
          text(player, "ui-history-maker-fee", trade.makerFee().toPlainString()),
          text(player, "ui-history-taker-fee", trade.takerFee().toPlainString()),
          text(player, "ui-history-created-at", trade.executedAt()));
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("BOOK", text(player, "ui-history-trade-title", trade.marketId(),
              trade.price().toPlainString())).lore(lore)).withSlot(slot++).build());
    }
    slot = 19;
    for (TransferRecord transfer : snapshot.transfers()) {
      if (slot >= 26) break;
      List<Component> lore = List.of(
          text(player, "ui-history-transfer-asset", transfer.assetId()),
          text(player, "ui-history-transfer-amount", transfer.amount().toPlainString()),
          text(player, "ui-history-transfer-status", transfer.status()),
          text(player, "ui-history-created-at", transfer.updatedAt()));
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("HOPPER", text(player, "ui-history-transfer-title", transfer.type())).lore(lore))
          .withSlot(slot++).build());
    }
    slot = 28;
    for (AccountLedgerEntry entry : snapshot.ledger()) {
      if (slot >= 44) break;
      if (slot == 35 || slot == 36) slot = 37;
      List<Component> lore = List.of(
          text(player, "ui-history-ledger-asset", entry.assetId()),
          text(player, "ui-history-ledger-amount", entry.amount().toPlainString()),
          text(player, "ui-history-created-at", entry.createdAt()));
      ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("WRITABLE_BOOK", text(player, "ui-history-ledger-title", entry.journalType())).lore(lore))
          .withSlot(slot++).build());
    }
    ExchangeMenuRequest opened = contexts.get(playerId).orElse(null);
    if (opened == null || !"history".equals(opened.menuName())) return;
    int currentPage = opened.page();
    if (currentPage > 1) {
      addNavigation(page, player, 46, "ARROW", "ui-history-previous", currentPage - 1);
    }
    int nextPage = currentPage + 1;
    ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("BOOK", text(player, "ui-history-page-info", currentPage, nextPage))
        .lore(List.of(text(player, "ui-guide-history"))))
        .withSlot(49).build());
    if (snapshot.hasNext()) {
      addNavigation(page, player, 52, "ARROW", "ui-history-next", nextPage);
    }
  }

  private void addNavigation(PlayerInstancePage page, Player player, int slot, String material,
                             String key, int targetPage) {
    UUID playerId = player.getUniqueId();
    ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of(material, text(player, key)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuRequest request = ExchangeMenuRequest.page("history", targetPage);
          contexts.put(playerId, request);
          ExchangeMenuNavigator.open(click.player(), ExchangeMenuPage.HISTORY);
        })).withSlot(slot).build());
  }

  private Component text(Player player, String key, Object... arguments) {
    if (messages == null) return Component.text(key);
    Locale locale = player.locale();
    return Component.text(messages.message(key, locale, arguments));
  }
}
