package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Small locale-aware adapter for player-visible exchange menu text. */
final class ExchangeUiMessages {
  private final AddonMessageService messages;

  ExchangeUiMessages(AddonMessageService messages) {
    this.messages = messages;
  }

  Component component(Player player, String key, Object... arguments) {
    return Component.text(text(player, key, arguments));
  }

  String text(Player player, String key, Object... arguments) {
    if (messages == null) return key;
    Locale locale = player.locale();
    return messages.message(key, locale, arguments);
  }
}
