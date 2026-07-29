package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Bukkit adapter that keeps the command router independent from player and menu APIs. */
public final class BukkitCommandActor implements CommandActor {
  private final Player player;
  private final AddonMessageService messages;
  private final Locale locale;
  private final MenuOpener menus;
  private final BiConsumer<Player, Runnable> completionScheduler;

  public BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus) {
    this(player, messages, locale, menus,
        (owner, completion) -> QuickShop.folia().getScheduler()
            .runAtEntityLater(owner, completion, 1L));
  }

  BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus,
      BiConsumer<Player, Runnable> completionScheduler) {
    this.player = Objects.requireNonNull(player, "player");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.menus = Objects.requireNonNull(menus, "menus");
    this.completionScheduler = Objects.requireNonNull(completionScheduler,
        "completionScheduler");
  }

  @Override
  public UUID accountId() {
    return player.getUniqueId();
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.hasPermission(permission);
  }

  @Override
  public void message(String key, Object... arguments) {
    player.sendMessage(messages.message(key, locale, arguments));
  }

  @Override
  public void dispatchCompletion(Runnable completion) {
    Objects.requireNonNull(completion, "completion");
    completionScheduler.accept(player, completion);
  }

  @Override
  public void openMenu(String menuName, int page) {
    menus.open(menuName, page);
  }

  @Override
  public void openMenu(ExchangeMenuRequest request) {
    menus.open(request);
  }

  @FunctionalInterface
  public interface MenuOpener {
    void open(String menuName, int page);

    default void open(ExchangeMenuRequest request) {
      open(request.menuName(), request.page());
    }
  }
}
