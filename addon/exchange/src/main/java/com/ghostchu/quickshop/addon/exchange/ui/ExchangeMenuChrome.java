package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Shared visual language, navigation and page states for the exchange menu. */
final class ExchangeMenuChrome {
  static final List<Integer> CONTENT_SLOTS = List.of(
      10, 11, 12, 13, 14, 15, 16,
      19, 20, 21, 22, 23, 24, 25,
      28, 29, 30, 31, 32, 33, 34,
      37, 38, 39, 40, 41, 42, 43);

  private static final int[] BORDER_SLOTS = {
      0, 1, 2, 3, 5, 6, 7, 8,
      9, 17, 18, 26, 27, 35, 36, 44,
      46, 48, 50, 52
  };

  private final ExchangeMenuContextStore contexts;
  private final ExchangeUiMessages messages;
  private final ExchangeClockDisplay clock;

  ExchangeMenuChrome(ExchangeMenuContextStore contexts, ExchangeUiMessages messages) {
    this(contexts, messages, ExchangeClockDisplay.disabled());
  }

  ExchangeMenuChrome(ExchangeMenuContextStore contexts, ExchangeUiMessages messages,
                     ExchangeClockDisplay clock) {
    this.contexts = contexts;
    this.messages = messages;
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  void prepare(PlayerInstancePage page, Player player, ExchangeMenuPage current, String guideKey) {
    UUID playerId = player.getUniqueId();
    page.getIcons(playerId).clear();
    for (int slot : BORDER_SLOTS) {
      ExchangePageIcons.add(page, playerId, icon("GRAY_STAINED_GLASS_PANE", Component.empty(), slot));
    }
    ExchangePageIcons.add(page, playerId, icon("NETHER_STAR",
        messages.component(player, titleKey(current)).color(NamedTextColor.GOLD)
            .decorate(TextDecoration.BOLD), 4));
    clock.now().ifPresent(display -> ExchangePageIcons.add(page, playerId,
        new IconBuilder(ItemStackCompat.of("CLOCK",
            messages.component(player, "ui-clock-title").color(NamedTextColor.YELLOW))
            .lore(List.of(
                Component.text(display.text()).color(NamedTextColor.WHITE),
                messages.component(player, "ui-clock-zone", display.zoneId())
                    .color(NamedTextColor.GRAY))))
            .withSlot(7).build()));
    ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("KNOWLEDGE_BOOK", messages.component(player, "ui-guide-title").color(NamedTextColor.AQUA))
        .lore(List.of(messages.component(player, guideKey).color(NamedTextColor.GRAY))))
        .withSlot(8).build());
    for (NavigationItem item : primaryNavigation(current)) {
      String material = item.active() ? "LIME_STAINED_GLASS_PANE" : item.material();
      Component title = messages.component(player, item.titleKey())
          .color(item.active() ? NamedTextColor.GREEN : NamedTextColor.WHITE);
      IconBuilder builder = new IconBuilder(ItemStackCompat.of(material, title)
          .lore(List.of(messages.component(player,
              item.active() ? "ui-nav-current" : "ui-nav-open").color(NamedTextColor.DARK_GRAY))));
      if (!item.active()) {
        builder.withActions(new RunnableAction(click -> {
          org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("qssuite-exchange");
          if (plugin != null) {
            plugin.getLogger().info("Nav clicked! dest=" + item.destination());
          }
          navigate(playerId, item.destination(), click.player());
        }));
      }
      ExchangePageIcons.add(page, playerId, builder.withSlot(item.slot()).build());
    }
    ExchangePageIcons.add(page, playerId, new IconBuilder(ItemStackCompat.of("BARRIER", messages.component(player, "ui-nav-close").color(NamedTextColor.RED)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuChrome.playCancelSound(player);
          player.closeInventory();
        })).withSlot(53).build());
  }

  void loading(PlayerInstancePage page, Player player, ExchangeMenuPage current, String guideKey) {
    prepare(page, player, current, guideKey);
    ExchangePageIcons.add(page, player.getUniqueId(), new IconBuilder(ItemStackCompat.of("CLOCK", messages.component(player, "ui-loading-title").color(NamedTextColor.YELLOW))
        .lore(List.of(messages.component(player, "ui-loading-detail").color(NamedTextColor.GRAY))))
        .withSlot(22).build());
  }

  void empty(PlayerInstancePage page, Player player, String key, String actionKey) {
    ExchangePageIcons.add(page, player.getUniqueId(), new IconBuilder(ItemStackCompat.of("LIGHT_GRAY_DYE", messages.component(player, key).color(NamedTextColor.GRAY))
        .lore(List.of(messages.component(player, actionKey).color(NamedTextColor.YELLOW))))
        .withSlot(22).build());
  }

  void error(PlayerInstancePage page, Player player, ExchangeMenuPage current, String guideKey,
             String key) {
    prepare(page, player, current, guideKey);
    ExchangePageIcons.add(page, player.getUniqueId(), new IconBuilder(ItemStackCompat.of("BARRIER", messages.component(player, key).color(NamedTextColor.RED))
        .lore(List.of(messages.component(player, "ui-error-retry").color(NamedTextColor.GRAY))))
        .withSlot(22).build());
  }

  void addBack(PlayerInstancePage page, Player player, ExchangeMenuPage current,
               ExchangeMenuRequest retainedRequest) {
    ExchangeMenuPage destination = backDestination(current);
    if (destination == null) return;
    ExchangePageIcons.add(page, player.getUniqueId(), new IconBuilder(ItemStackCompat.of("ARROW", messages.component(player, "ui-nav-back").color(NamedTextColor.YELLOW)))
        .withActions(new RunnableAction(click -> {
          ExchangeMenuChrome.playCancelSound(player);
          contexts.put(player.getUniqueId(), backRequest(current, retainedRequest));
          ExchangeMenuNavigator.open(click.player(), destination);
        })).withSlot(46).build());
  }

  private void navigate(UUID playerId, ExchangeMenuPage destination,
                        net.tnemc.menu.core.compatibility.MenuPlayer menuPlayer) {
    contexts.put(playerId, ExchangeMenuRequest.page(destination.menuName()));
    playNavigationSound(menuPlayer);
    ExchangeMenuNavigator.open(menuPlayer, destination);
  }

  private void playNavigationSound(net.tnemc.menu.core.compatibility.MenuPlayer menuPlayer) {
    Player player = org.bukkit.Bukkit.getPlayer(menuPlayer.identifier());
    if (player != null) {
      player.playSound(player.getLocation(), Sound.UI_STONECUTTER_SELECT_RECIPE, 0.7f, 1.2f);
    }
  }

  static void playConfirmSound(Player player) {
    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
  }

  static void playCancelSound(Player player) {
    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
  }

  static void playErrorSound(Player player) {
    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 0.8f);
  }

  private static net.tnemc.menu.core.icon.Icon icon(String material, Component title, int slot) {
    return new IconBuilder(ItemStackCompat.of(material, title))
        .withSlot(slot).build();
  }

  static List<NavigationItem> primaryNavigation(ExchangeMenuPage current) {
    return List.of(
        new NavigationItem(45, ExchangeMenuPage.MARKETS, current == ExchangeMenuPage.MARKETS,
            "COMPASS", "ui-nav-markets"),
        new NavigationItem(47, ExchangeMenuPage.ORDERS, current == ExchangeMenuPage.ORDERS,
            "PAPER", "ui-nav-orders"),
        new NavigationItem(49, ExchangeMenuPage.ASSETS, current == ExchangeMenuPage.ASSETS,
            "ENDER_CHEST", "ui-nav-assets"),
        new NavigationItem(51, ExchangeMenuPage.HISTORY, current == ExchangeMenuPage.HISTORY,
            "WRITABLE_BOOK", "ui-nav-history"));
  }

  static ExchangeMenuPage backDestination(ExchangeMenuPage current) {
    return switch (current) {
      case MARKET_DETAIL -> ExchangeMenuPage.MARKETS;
      case ORDER_CONFIRM -> ExchangeMenuPage.MARKET_DETAIL;
      case CANCEL_CONFIRM -> ExchangeMenuPage.ORDERS;
      case TRANSFER_CONFIRM -> ExchangeMenuPage.ASSETS;
      default -> null;
    };
  }

  static ExchangeMenuRequest backRequest(ExchangeMenuPage current,
                                         ExchangeMenuRequest retainedRequest) {
    ExchangeMenuPage destination = backDestination(current);
    if (destination == null) {
      throw new IllegalArgumentException("page does not have a back destination: " + current);
    }
    if (destination == ExchangeMenuPage.MARKET_DETAIL) {
      if (retainedRequest == null || retainedRequest.marketId() == null
          || retainedRequest.marketId().isBlank()) {
        throw new IllegalArgumentException("market detail return requires a market request");
      }
      return ExchangeMenuRequest.market(retainedRequest.marketId());
    }
    return ExchangeMenuRequest.page(destination.menuName());
  }

  private static String titleKey(ExchangeMenuPage page) {
    return switch (page) {
      case MARKETS -> "ui-title-markets";
      case MARKET_DETAIL -> "ui-title-market-detail";
      case ORDER_CONFIRM -> "ui-title-order-confirm";
      case CANCEL_CONFIRM -> "ui-title-cancel-confirm";
      case TRANSFER_CONFIRM -> "ui-title-transfer-confirm";
      case ORDERS -> "ui-title-orders";
      case ASSETS -> "ui-title-assets";
      case HISTORY -> "ui-title-history";
      case ADMIN -> "ui-title-admin";
    };
  }

  record NavigationItem(int slot, ExchangeMenuPage destination, boolean active,
                        String material, String titleKey) {
  }
}
