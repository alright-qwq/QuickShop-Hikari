package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.List;
import java.util.UUID;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Read-only landing page for independently permissioned administrator operations. */
final class AdminPage {
  private final ExchangeUiMessages messages;
  private final ExchangeMenuChrome chrome;

  AdminPage(ExchangeMenuContextStore contexts, AddonMessageService messages) {
    this(contexts, messages, ExchangeClockDisplay.disabled());
  }

  AdminPage(ExchangeMenuContextStore contexts, AddonMessageService messages,
            ExchangeClockDisplay clock) {
    this.messages = new ExchangeUiMessages(messages);
    this.chrome = new ExchangeMenuChrome(contexts, this.messages, clock);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    if (player == null || !player.isOnline()) return;
    chrome.prepare(page, player, ExchangeMenuPage.ADMIN, "ui-guide-admin");
    add(page, playerId, player, "quickshop.exchange.admin.market", "COMPASS", "ui-admin-market", 19);
    add(page, playerId, player, "quickshop.exchange.admin.orders", "PAPER", "ui-admin-orders", 21);
    add(page, playerId, player, "quickshop.exchange.admin.recovery", "ANVIL", "ui-admin-recovery", 23);
    add(page, playerId, player, "quickshop.exchange.admin.audit", "BOOK", "ui-admin-audit", 25);
  }

  private void add(PlayerInstancePage page, UUID playerId, Player player,
                   String permission, String material, String titleKey, int slot) {
    if (!player.hasPermission(permission)) return;
    ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of(material, messages.component(player, titleKey))
        .lore(List.of(messages.component(player, "ui-admin-command-only"))))
        .withSlot(slot).build());
  }
}
