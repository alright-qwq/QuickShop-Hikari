package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import java.util.Objects;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.manager.MenuManager;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.bukkit.entity.Player;

/** Owns exchange viewer lifecycle without stopping QuickShop's global menu manager. */
public final class ExchangeMenuService implements AutoCloseable {
  private final ExchangeMenu menu;

  public ExchangeMenuService(ExchangeViewService views) {
    menu = new ExchangeMenu(Objects.requireNonNull(views, "views"));
    MenuManager.instance().addMenu(menu);
  }

  public void open(Player player, String requestedMenu, int requestedPage) {
    Objects.requireNonNull(player, "player");
    if (!ExchangeMenu.NAME.equals(requestedMenu) && !"markets".equals(requestedMenu)) {
      requestedPage = 1;
    }
    MenuViewer viewer = new MenuViewer(player.getUniqueId());
    MenuManager.instance().addViewer(viewer);
    MenuPlayer menuPlayer = QuickShop.getInstance().createMenuPlayer(player);
    MenuManager.instance().open(ExchangeMenu.NAME, Math.max(1, requestedPage), menuPlayer);
  }

  @Override
  public void close() {
    // Viewers are per-player state; never stop or reset the global QuickShop menu manager.
  }
}
