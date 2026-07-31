package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.Map;
import java.util.UUID;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.icon.Icon;

/** Preserves all per-player icons despite TNML PlayerInstancePage.addIcon replacing its instance. */
final class ExchangePageIcons {
  private ExchangePageIcons() {}

  static void add(PlayerInstancePage page, UUID playerId, Icon icon) {
    if (!page.hasInstance(playerId)) {
      page.addIcon(playerId, icon);
      return;
    }
    page.getIcons(playerId).put(icon.slot(), icon);
  }

  static void reset(PlayerInstancePage page, UUID playerId) {
    if (page.hasInstance(playerId)) {
      page.getIcons(playerId).clear();
    }
  }

  static Map<Integer, Icon> icons(PlayerInstancePage page, UUID playerId) {
    return page.getIcons(playerId);
  }
}
