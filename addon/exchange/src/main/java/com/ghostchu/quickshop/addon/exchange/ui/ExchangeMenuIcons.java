package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.tnemc.menu.core.PlayerInstancePage;
import org.bukkit.entity.Player;
import net.tnemc.menu.core.icon.Icon;

/** Safe wrappers around TNML's per-player page APIs. */
final class ExchangeMenuIcons {
  private static final ConcurrentMap<PlayerInstancePage, Map<UUID, Set<Integer>>>
      LAST_RENDERED_SLOTS = new ConcurrentHashMap<>();

  private ExchangeMenuIcons() {
  }

  /**
   * Adds or replaces an icon without relying on {@link PlayerInstancePage#addIcon(UUID, Icon)}.
   * The library method replaces a player's existing icon map on every subsequent call, leaving
   * dynamic pages with only the most recently added icon.
   */
  static void forget(UUID playerId) {
    LAST_RENDERED_SLOTS.values().forEach(byPlayer -> byPlayer.remove(playerId));
  }

  static void forgetAll() {
    LAST_RENDERED_SLOTS.clear();
  }

  static void add(PlayerInstancePage page, UUID playerId, Icon icon) {
    if (!page.hasInstance(playerId)) {
      // The first add creates the per-player instance; subsequent calls below mutate it in place.
      page.addIcon(playerId, icon);
    }
    page.getIcons(playerId).put(icon.slot(), icon);
  }

  /** Replaces the current icon set and remembers slots that may still be visible. */
  static void clear(PlayerInstancePage page, UUID playerId) {
    if (page.hasInstance(playerId)) {
      Set<Integer> previousSlots = Set.copyOf(page.getIcons(playerId).keySet());
      Map<UUID, Set<Integer>> byPlayer = LAST_RENDERED_SLOTS
          .computeIfAbsent(page, key -> new ConcurrentHashMap<>());
      if (previousSlots.isEmpty()) {
        byPlayer.remove(playerId);
      } else {
        byPlayer.put(playerId, previousSlots);
      }
      page.getIcons(playerId).clear();
    }
  }

  /** Applies page icons to an already-open inventory (used after async renders). */
  static void update(Player player, PlayerInstancePage page) {
    net.tnemc.menu.core.compatibility.MenuPlayer menuPlayer =
        ExchangeMenuPlatform.menuPlayer(player);
    UUID playerId = player.getUniqueId();
    Map<Integer, Icon> icons = page.getIcons(playerId);
    Set<Integer> staleSlots =
        LAST_RENDERED_SLOTS.getOrDefault(page, Map.of()).getOrDefault(
            playerId, Set.of());
    for (Integer slot : staleSlots) {
      if (!icons.containsKey(slot)) {
        menuPlayer.inventory().updateInventory(slot, ExchangeMenuPlatform.stack().of("AIR", 1));
      }
    }
    icons.forEach((slot, icon) ->
        menuPlayer.inventory().updateInventory(slot, icon.getItem(menuPlayer)));

    Map<UUID, Set<Integer>> tracked = LAST_RENDERED_SLOTS.get(page);
    if (tracked != null) {
      tracked.computeIfPresent(playerId, (id, slots) -> {
        Set<Integer> remaining = new HashSet<>(icons.keySet());
        remaining.retainAll(staleSlots);
        return remaining.isEmpty() ? null : remaining;
      });
    }
  }
}
