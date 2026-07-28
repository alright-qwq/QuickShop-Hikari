package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.Objects;
import java.util.UUID;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.manager.MenuManager;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.bukkit.entity.Player;

/** Owns exchange viewer lifecycle without stopping QuickShop's global menu manager. */
public final class ExchangeMenuService implements AutoCloseable {
  private final ExchangeMenu menu;
  private final ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();

  public ExchangeMenuService(ExchangeViewService views) {
    menu = new ExchangeMenu(Objects.requireNonNull(views, "views"));
    MenuManager.instance().addMenu(menu);
  }

  public void open(Player player, String requestedMenu, int requestedPage) {
    open(player, ExchangeMenuRequest.page(requestedMenu, requestedPage));
  }

  public void open(Player player, ExchangeMenuRequest request) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(request, "request");
    contexts.put(player.getUniqueId(), request);
    MenuViewer viewer = new MenuViewer(player.getUniqueId());
    MenuManager.instance().addViewer(viewer);
    MenuPlayer menuPlayer = QuickShop.getInstance().createMenuPlayer(player);
    MenuManager.instance().open(ExchangeMenu.NAME, request.page(), menuPlayer);
  }

  public java.util.Optional<ExchangeMenuRequest> requestFor(UUID playerId) {
    return contexts.get(playerId);
  }

  public void playerClosed(UUID playerId) {
    contexts.remove(playerId);
  }

  @Override
  public void close() {
    contexts.close();
    // Viewers are per-player state; never stop or reset the global QuickShop menu manager.
  }
}
