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
  private final Runnable handbookClaim;

  public BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus) {
    this(player, messages, locale, menus, () -> {});
  }

  public BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus,
      Runnable handbookClaim) {
    this(player, messages, locale, menus,
        (owner, completion) -> QuickShop.folia().getScheduler()
            .runAtEntityLater(owner, completion, 1L),
        handbookClaim);
  }

  BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus,
      BiConsumer<Player, Runnable> completionScheduler) {
    this(player, messages, locale, menus, completionScheduler, () -> {});
  }

  BukkitCommandActor(
      Player player, AddonMessageService messages, Locale locale, MenuOpener menus,
      BiConsumer<Player, Runnable> completionScheduler, Runnable handbookClaim) {
    this.player = Objects.requireNonNull(player, "player");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.locale = Objects.requireNonNull(locale, "locale");
    this.menus = Objects.requireNonNull(menus, "menus");
    this.completionScheduler = Objects.requireNonNull(completionScheduler,
        "completionScheduler");
    this.handbookClaim = Objects.requireNonNull(handbookClaim, "handbookClaim");
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
  public boolean isPlayer() {
    return true;
  }

  /** Exposes the live player only to Bukkit platform adapters. */
  public Player player() {
    return player;
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
  public void claimHandbook() {
    completionScheduler.accept(player, handbookClaim);
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
