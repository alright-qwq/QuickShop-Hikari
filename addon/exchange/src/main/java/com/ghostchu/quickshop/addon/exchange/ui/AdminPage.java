package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Read-only landing page for independently permissioned administrator operations. */
final class AdminPage {
  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    UUID playerId = callback.getPlayer().identifier();
    Player player = Bukkit.getPlayer(playerId);
    page.getIcons(playerId).clear();
    page.setLockEmptySlots(true);
    if (player == null) return;
    add(page, playerId, player, "quickshop.exchange.admin.market", "COMPASS", "Market controls",
        "/qse admin market pause <market> <reason>", 19);
    add(page, playerId, player, "quickshop.exchange.admin.orders", "PAPER", "Order controls",
        "/qse admin order cancel <order-id> <reason>", 21);
    add(page, playerId, player, "quickshop.exchange.admin.recovery", "ANVIL", "Recovery controls",
        "/qse admin transfer review list|show|resolve", 23);
    add(page, playerId, player, "quickshop.exchange.admin.audit", "BOOK", "Audit controls",
        "/qse admin audit reconcile|export <from> <to>", 25);
  }

  private static void add(PlayerInstancePage page, UUID playerId, Player player,
                          String permission, String material, String title, String usage, int slot) {
    if (!player.hasPermission(permission)) return;
    page.addIcon(playerId, new IconBuilder(QuickShop.getInstance().stack().of(material, 1)
        .customName(Component.text(title)).lore(java.util.List.of(Component.text(usage))))
        .withActions(new RunnableAction(click -> player.sendMessage(Component.text(usage))))
        .withSlot(slot).build());
  }
}
