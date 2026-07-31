package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.tnemc.menu.core.compatibility.MenuPlayer;
import net.tnemc.menu.core.manager.MenuManager;
import net.tnemc.menu.core.viewer.MenuViewer;
import org.bukkit.entity.Player;

/** Owns exchange viewer lifecycle without stopping QuickShop's global menu manager. */
public final class ExchangeMenuService implements AutoCloseable {
  private final ExchangeMenu menu;
  private final ExchangeRequestSubmitter submitter;
  private final ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
  private final ExchangeMenuLifecycle lifecycle = new ExchangeMenuLifecycle(contexts, playerId -> {
    com.ghostchu.quickshop.menu.shared.GuiChatInputManager.getInstance().cancelInput(playerId);
    MenuManager.instance().removeViewer(playerId);
  });

  public ExchangeMenuService(ExchangeViewService views) {
    this(views, null, RolloutPolicy.DISABLED, null);
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter) {
    this(views, submitter, RolloutPolicy.DISABLED, null);
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter,
                             RolloutPolicy rollout, AddonMessageService messages) {
    this(views, submitter, rollout, messages, ExchangeClockDisplay.disabled());
  }

  public ExchangeMenuService(ExchangeViewService views, ExchangeRequestSubmitter submitter,
                             RolloutPolicy rollout, AddonMessageService messages,
                             ExchangeClockDisplay clock) {
    this.submitter = submitter;
    menu = new ExchangeMenu(Objects.requireNonNull(views, "views"), contexts, submitter,
        Objects.requireNonNull(rollout, "rollout"), messages,
        Objects.requireNonNull(clock, "clock"));
    MenuManager.instance().addMenu(menu);
  }

  public void open(Player player, String requestedMenu, int requestedPage) {
    open(player, ExchangeMenuRequest.page(requestedMenu, requestedPage));
  }

  public void open(Player player, ExchangeMenuRequest request) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(request, "request");
    contexts.put(player.getUniqueId(), request);
    MenuManager.instance().recentlyClosed().remove(player.getUniqueId());
    boolean viewerAlreadyOpen = MenuManager.instance().findViewer(player.getUniqueId()).isPresent();
    MenuViewer viewer = new MenuViewer(player.getUniqueId());
    MenuManager.instance().addViewer(viewer);
    MenuPlayer menuPlayer = QuickShop.getInstance().createMenuPlayer(player);
    ExchangeMenuPage destination = ExchangeMenuPage.forName(request.menuName());
    ExchangeMenuNavigator.open(menuPlayer, destination, viewerAlreadyOpen);
  }

  public java.util.Optional<ExchangeMenuRequest> requestFor(UUID playerId) {
    return contexts.get(playerId);
  }

  public void playerClosed(UUID playerId) {
    lifecycle.playerQuit(playerId);
  }

  public void inventoryClosed(UUID playerId, String title) {
    java.util.Optional<MenuViewer> viewer = MenuManager.instance().findViewer(playerId);
    lifecycle.inventoryClosed(playerId, title, viewer.isPresent(),
        viewer.map(MenuViewer::menu).orElse(null));
  }

  static void closeInventoryAtOwner(Player player, BiConsumer<Player, Runnable> scheduler) {
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(scheduler, "scheduler");
    try {
      scheduler.accept(player, player::closeInventory);
    } catch (RuntimeException ignored) {
      // During plugin disable, the platform may already reject new entity tasks. Never fall back
      // to cross-thread inventory access; viewer and context cleanup still completes locally.
    }
  }

  @Override
  public void close() {
    for (UUID playerId : contexts.playerIds()) {
      com.ghostchu.quickshop.menu.shared.GuiChatInputManager.getInstance().cancelInput(playerId);
      Player player = org.bukkit.Bukkit.getPlayer(playerId);
      if (player != null && player.isOnline()) {
        closeInventoryAtOwner(player,
            (owner, action) -> QuickShop.folia().getScheduler().runAtEntityLater(owner, action, 1L));
      }
      MenuManager.instance().removeViewer(playerId);
    }
    if (submitter instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IllegalStateException("failed to close exchange request submitter", failure);
      }
    }
    contexts.close();
    // Viewers are per-player state; never stop or reset the global QuickShop menu manager.
  }
}
