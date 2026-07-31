package com.ghostchu.quickshop.addon.exchange.ui;

import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.viewer.CoreStatus;
import net.tnemc.menu.core.viewer.ViewerStatus;

/** Opens replacement exchange inventories without TNML treating the switch as a menu close. */
final class ExchangeMenuNavigator {
  private ExchangeMenuNavigator() {}

  static void open(MenuPlayer player, ExchangeMenuPage destination) {
    open(player, destination, true);
  }

  static void open(MenuPlayer player, ExchangeMenuPage destination, boolean viewerAlreadyOpen) {
    ViewerStatus status = switchingStatus(viewerAlreadyOpen);
    if (status != null) {
      player.status(status);
    }
    player.inventory().openMenu(player, ExchangeMenu.NAME, destination.page());
  }

  static ViewerStatus switchingStatus(boolean viewerAlreadyOpen) {
    return viewerAlreadyOpen ? CoreStatus.SWITCHING : null;
  }
}
