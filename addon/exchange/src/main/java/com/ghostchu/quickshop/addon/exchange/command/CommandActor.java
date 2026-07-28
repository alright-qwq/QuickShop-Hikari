package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;

public interface CommandActor {
  UUID accountId();

  boolean hasPermission(String permission);

  void message(String key, Object... arguments);

  void openMenu(String menuName, int page);

  /** Opens a page while retaining all typed state needed for a later submission. */
  default void openMenu(ExchangeMenuRequest request) {
    openMenu(request.menuName(), request.page());
  }
}
