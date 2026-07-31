package com.ghostchu.quickshop.addon.exchange.ui;

import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.handlers.MenuClickHandler;

/** Routes TNML's base Page click entry to the current player's icon map. */
final class ExchangePlayerPage extends PlayerInstancePage {
  private ExchangePlayerPage(int pageNumber) {
    super(pageNumber);
  }

  static PlayerInstancePage create(int pageNumber) {
    return new ExchangePlayerPage(pageNumber);
  }

  @Override
  public boolean onClick(MenuClickHandler handler) {
    return super.onClick(handler.player().identifier(), handler);
  }
}
