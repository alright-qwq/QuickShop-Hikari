package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;

public interface CommandActor {
  UUID accountId();

  boolean hasPermission(String permission);

  /** Whether this actor has a live player-owned world context. */
  default boolean isPlayer() {
    return false;
  }

  void message(String key, Object... arguments);

  /** Dispatches an asynchronous command completion at the actor's thread-owning boundary. */
  default void dispatchCompletion(Runnable completion) {
    completion.run();
  }

  /** Requests a portable Exchange handbook without coupling the router to Bukkit inventory APIs. */
  default void claimHandbook() {
    message("command-invalid");
  }

  void openMenu(String menuName, int page);

  /** Opens a page while retaining all typed state needed for a later submission. */
  default void openMenu(ExchangeMenuRequest request) {
    openMenu(request.menuName(), request.page());
  }
}
