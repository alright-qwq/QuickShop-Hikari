package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;

public interface CommandActor {
  UUID accountId();

  boolean hasPermission(String permission);

  void message(String key, Object... arguments);

  void openMenu(String menuName, int page);
}
