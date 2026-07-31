package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Authenticates portable Exchange handbooks and opens the market menu on the player owner. */
public final class ExchangeHandbookListener implements Listener {
  private final ExchangeHandbookService handbooks;
  private final RolloutPolicy rollout;
  private final AddonMessageService messages;
  private final Function<Player, Locale> localeResolver;
  private final Predicate<Player> usePermission;
  private final BiConsumer<Player, Runnable> entityScheduler;
  private final Consumer<Player> marketOpener;

  public ExchangeHandbookListener(
      ExchangeHandbookService handbooks,
      RolloutPolicy rollout,
      AddonMessageService messages,
      Function<Player, Locale> localeResolver,
      Predicate<Player> usePermission,
      BiConsumer<Player, Runnable> entityScheduler,
      Consumer<Player> marketOpener) {
    this.handbooks = Objects.requireNonNull(handbooks, "handbooks");
    this.rollout = Objects.requireNonNull(rollout, "rollout");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.localeResolver = Objects.requireNonNull(localeResolver, "localeResolver");
    this.usePermission = Objects.requireNonNull(usePermission, "usePermission");
    this.entityScheduler = Objects.requireNonNull(entityScheduler, "entityScheduler");
    this.marketOpener = Objects.requireNonNull(marketOpener, "marketOpener");
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    Action action = event.getAction();
    if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    if (event.getHand() != EquipmentSlot.HAND || !handbooks.isHandbook(event.getItem())) {
      return;
    }

    event.setCancelled(true);
    Player player = event.getPlayer();
    if (!handbooks.enabled()) {
      message(player, "handbook-disabled");
      return;
    }
    if (!usePermission.test(player)) {
      message(player, "permission-denied");
      return;
    }
    if (!rollout.allows(player.getUniqueId())) {
      message(player, "rollout-not-allowed");
      return;
    }
    entityScheduler.accept(player, () -> marketOpener.accept(player));
  }

  private void message(Player player, String key) {
    Locale locale = localeResolver.apply(player);
    player.sendMessage(messages.message(key, locale == null ? Locale.US : locale));
  }
}
