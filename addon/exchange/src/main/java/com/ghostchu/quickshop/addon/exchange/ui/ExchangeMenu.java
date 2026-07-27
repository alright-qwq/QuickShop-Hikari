package com.ghostchu.quickshop.addon.exchange.ui;

import net.tnemc.menu.core.Menu;
import net.tnemc.menu.core.PlayerInstancePage;

/** TNML menu root for the exchange. */
public final class ExchangeMenu extends Menu {
  public static final String NAME = "qs:exchange";

  public ExchangeMenu(ExchangeViewService views) {
    name = NAME;
    title = "QuickShop Exchange";
    rows = 6;
    PlayerInstancePage markets = new PlayerInstancePage(1);
    markets.setOpen(new MarketListPage(views)::open);
    addPage(markets);
  }
}
