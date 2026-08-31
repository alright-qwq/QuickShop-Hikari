package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.tnemc.menu.core.PlayerInstancePage;
import net.tnemc.menu.core.builder.IconBuilder;
import net.tnemc.menu.core.callbacks.page.PageOpenCallback;
import net.tnemc.menu.core.icon.action.impl.RunnableAction;
import net.tnemc.menu.core.manager.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** A QuickShop-like, click-first order editor. */
final class OrderBuilderPage {
  private static final List<Long> QUANTITIES = List.of(1L, 2L, 4L, 8L, 16L, 32L, 64L);
  private static final int[] PRICE_PERMILLE = {950, 1_000, 1_050, 1_100};

  private final ExchangeMenuContextStore contexts;
  private final RolloutPolicy rollout;
  private final java.util.function.Supplier<UUID> requestIds;
  private final ExchangeUiMessages messages;
  private final MenuNavigation navigation;
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  OrderBuilderPage(ExchangeMenuContextStore contexts, AddonMessageService messages,
                   java.util.function.Supplier<UUID> requestIds, RolloutPolicy rollout) {
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.messages = new ExchangeUiMessages(messages);
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.rollout = rollout == null ? RolloutPolicy.DISABLED : rollout;
    this.navigation = new MenuNavigation(contexts);
  }

  void playerQuit(UUID playerId) {
    sessions.remove(playerId);
  }

  void playerQuitAll() {
    sessions.clear();
  }

  void start(Player player, MarketRow row, OrderSide side, OrderType type) {
    String denial = new OrderEntryAccess(rollout).denial(player.getUniqueId(), row.status(),
        type, player::hasPermission).orElse(null);
    if (denial != null) {
      player.sendMessage(messages.component(player, denial));
      return;
    }
    contexts.put(player.getUniqueId(), ExchangeMenuRequest.market(row.marketId()));
    sessions.put(player.getUniqueId(),
        Session.initial(requestIds.get(), player.getUniqueId(), row, side, type));
    openPage(player);
  }

  void open(PageOpenCallback callback) {
    if (!(callback.getPage() instanceof PlayerInstancePage page)) return;
    Player player = Bukkit.getPlayer(callback.getPlayer().identifier());
    if (player == null || !player.isOnline()) return;
    Session session = sessions.get(player.getUniqueId());
    if (session == null) {
      contexts.put(player.getUniqueId(), ExchangeMenuRequest.page(
          ExchangeMenuPage.MARKETS.menuName()));
      MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.MARKETS.page(),
          ExchangeMenuPlatform.menuPlayer(player));
      return;
    }
    render(page, player, session);
  }

  private void render(PlayerInstancePage page, Player player, Session session) {
    UUID playerId = player.getUniqueId();
    ExchangeMenuIcons.clear(page, playerId);
    page.setLockEmptySlots(true);
    addInfo(page, player, session);
    addMode(page, player, session, OrderSide.BUY, OrderType.LIMIT, 19, "ui-order-limit-buy");
    addMode(page, player, session, OrderSide.SELL, OrderType.LIMIT, 20, "ui-order-limit-sell");
    addMode(page, player, session, OrderSide.BUY, OrderType.MARKET, 21, "ui-order-market-buy");
    addMode(page, player, session, OrderSide.SELL, OrderType.MARKET, 22, "ui-order-market-sell");
    addQuantityControls(page, player, session);
    addPriceControls(page, player, session);
    addNavigation(page, player);
    addConfirm(page, player, session);
    addFiller(page, player);
    ExchangeMenuIcons.update(player, page);
  }

  private void addInfo(PlayerInstancePage page, Player player, Session session) {
    List<Component> lore = List.of(
        messages.component(player, "ui-order-builder-market", session.market().displayName()).color(
            net.kyori.adventure.text.format.NamedTextColor.AQUA),
        messages.component(player, "ui-order-builder-reference",
            formatPrice(Session.reference(session.market(), session.side()))).color(
            net.kyori.adventure.text.format.NamedTextColor.YELLOW),
        messages.component(player, "ui-order-builder-quantity", session.quantity()).color(
            net.kyori.adventure.text.format.NamedTextColor.GREEN),
        messages.component(player, session.priceKey(), formatPrice(session.price())).color(
            net.kyori.adventure.text.format.NamedTextColor.GOLD),
        messages.component(player, "ui-order-builder-total", formatPrice(session.estimate())).color(
            net.kyori.adventure.text.format.NamedTextColor.WHITE));
    ExchangeMenuIcons.add(page, player.getUniqueId(), new IconBuilder(
        ExchangeMenuPlatform.stack().of("ENCHANTED_BOOK", 1)
            .customName(messages.component(player, "ui-order-builder-title").color(
            net.kyori.adventure.text.format.NamedTextColor.AQUA)).lore(lore))
        .withSlot(4).build());
  }

  private void addMode(PlayerInstancePage page, Player player, Session session, OrderSide side,
                       OrderType type, int slot, String key) {
    boolean selected = session.side() == side && session.type() == type;
    Runnable action = selected ? null : () -> {
      session.switchTo(side, type);
      openPage(player);
    };
    addClickable(page, player, slot, selected
            ? "LIME_STAINED_GLASS_PANE" : "GRAY_STAINED_GLASS_PANE",
        messages.component(player, key),
        List.of(messages.component(player, selected ? "ui-order-builder-selected"
            : "ui-order-builder-click-select")), action);
  }

  private void addQuantityControls(PlayerInstancePage page, Player player, Session session) {
    addPageItem(page, player, 27, "HOPPER", messages.component(player,
        "ui-order-builder-quantity", session.quantity()).color(
        net.kyori.adventure.text.format.NamedTextColor.AQUA),
        List.of(messages.component(player, "ui-order-builder-quantity-help")));
    int slot = 28;
    for (long value : QUANTITIES) {
      final long preset = value;
      boolean selected = session.quantity() == preset;
      addClickable(page, player, slot, preset == 1 ? "PAPER" : "BOOK",
          messages.component(player, "ui-order-builder-quantity-value", preset),
          List.of(messages.component(player, selected ? "ui-order-builder-selected"
              : "ui-order-builder-click-select")), selected ? null
          : () -> {
            session.quantity(preset);
            openPage(player);
          });
      slot++;
    }
    addChatInput(page, player, 35, "WRITABLE_BOOK", messages.component(player,
            "ui-order-builder-custom-quantity", session.quantity()).color(
            net.kyori.adventure.text.format.NamedTextColor.YELLOW),
        "ui-order-builder-quantity-prompt", raw -> session.quantity(parseQuantity(raw)),
        () -> player.sendMessage(messages.component(player, "ui-order-builder-quantity-invalid")));
    addClickable(page, player, 36, "LIME_DYE", messages.component(player,
            "ui-order-builder-increase-quantity", session.quantity()).color(
            net.kyori.adventure.text.format.NamedTextColor.GREEN),
        List.of(messages.component(player, "ui-order-builder-click-select")),
        () -> {
          session.quantity(session.quantity() + 1);
          openPage(player);
        });
    addClickable(page, player, 37, "RED_DYE", messages.component(player,
            "ui-order-builder-decrease-quantity", session.quantity()).color(
            net.kyori.adventure.text.format.NamedTextColor.RED),
        List.of(messages.component(player, "ui-order-builder-click-select")),
        () -> {
          session.quantity(Math.max(1L, session.quantity() - 1));
          openPage(player);
        });
  }

  private void addPriceControls(PlayerInstancePage page, Player player, Session session) {
    BigDecimal reference = Session.reference(session.market(), session.side());
    BigDecimal current = session.price();
    addPageItem(page, player, 38, "GOLD_INGOT",
        messages.component(player, session.priceKey(), formatPrice(current)).color(
            net.kyori.adventure.text.format.NamedTextColor.YELLOW),
        List.of(messages.component(player, session.type() == OrderType.LIMIT
            ? "ui-order-builder-price-limit-help" : "ui-order-builder-price-market-help")));
    if (reference == null || reference.signum() <= 0) {
      addPageItem(page, player, 39, "BARRIER",
        messages.component(player, "ui-order-builder-no-reference"),
        List.of(messages.component(player, "ui-order-builder-custom-price")));
      return;
    }
    int slot = 39;
    for (int value : PRICE_PERMILLE) {
      final int multiplier = value;
      BigDecimal preset = Session.permille(reference, multiplier);
      boolean selected = session.priceMatches(preset);
      addClickable(page, player, slot, selected ? "GOLD_BLOCK" : "GOLD_INGOT",
          messages.component(player, "ui-order-builder-price-preset", Session.percent(multiplier),
              formatPrice(preset)),
          List.of(messages.component(player, selected ? "ui-order-builder-selected"
              : "ui-order-builder-click-select")),
          selected ? null : () -> {
            session.price(preset);
            openPage(player);
          });
      slot++;
    }
    addChatInput(page, player, 43, "NAME_TAG", messages.component(player,
            "ui-order-builder-custom-price", formatPrice(current)).color(
            net.kyori.adventure.text.format.NamedTextColor.YELLOW),
        session.type() == OrderType.LIMIT ? "ui-order-builder-price-limit-prompt"
            : "ui-order-builder-price-market-prompt",
        raw -> session.price(parsePrice(raw)),
        () -> player.sendMessage(messages.component(player, invalidKey(session.type()))));
  }

  private void addNavigation(PlayerInstancePage page, Player player) {
    ExchangeMenuIcons.add(page, player.getUniqueId(), new IconBuilder(
        ExchangeMenuPlatform.stack().of("ARROW", 1)
            .customName(messages.component(player, "ui-shared-back")))
        .withActions(new RunnableAction(click -> {
          contexts.put(player.getUniqueId(), ExchangeMenuRequest.market(
              contexts.get(player.getUniqueId()).map(ExchangeMenuRequest::marketId)
                  .filter(value -> value != null && !value.isBlank()).orElse(null)));
          MenuManager.instance().open(ExchangeMenu.NAME,
              ExchangeMenuPage.MARKET_DETAIL.page(), click.player());
        }))
        .withSlot(45).build());
    navigation.addClose(page, player, messages);
  }

  private void addChatInput(PlayerInstancePage page, Player player, int slot, String material,
                            Component name, String promptKey, java.util.function.Consumer<String> parser,
                            Runnable invalid) {
    ExchangeChatInputManager.getInstance().requestInput(player, raw -> {
      try {
        parser.accept(raw);
      } catch (RuntimeException failure) {
        invalid.run();
        return true;
      }
      return true;
    }, messages.text(player, promptKey), ExchangeMenu.NAME, ExchangeMenuPage.ORDER_ENTRY.page());
    player.closeInventory();
  }

  private void addFiller(PlayerInstancePage page, Player player) {
    for (int slot : new int[]{0, 1, 2, 3, 5, 6, 7, 9, 10, 11, 12, 13,
        14, 15, 16, 17, 18, 23, 24, 25, 26, 44, 46, 47, 48,
        50, 51, 52, 53}) {
      ExchangeMenuIcons.add(page, player.getUniqueId(), new IconBuilder(
          ExchangeMenuPlatform.stack().of("GRAY_STAINED_GLASS_PANE", 1)
              .customName(Component.text(" ")))
          .withSlot(slot).build());
    }
  }

  private void addClickable(PlayerInstancePage page, Player player, int slot, String material,
                            Component name, List<Component> lore, Runnable action) {
    var builder = new IconBuilder(ExchangeMenuPlatform.stack().of(material, 1).customName(name)
        .lore(lore));
    if (action != null) {
      builder.withActions(new RunnableAction(click -> {
        action.run();
      }));
    }
    ExchangeMenuIcons.add(page, player.getUniqueId(), builder.withSlot(slot).build());
  }

  private void addPageItem(PlayerInstancePage page, Player player, int slot, String material,
                           Component name, List<Component> lore) {
    ExchangeMenuIcons.add(page, player.getUniqueId(), new IconBuilder(
        ExchangeMenuPlatform.stack().of(material, 1).customName(name).lore(lore))
        .withSlot(slot).build());
  }

  private void addConfirm(PlayerInstancePage page, Player player, Session session) {
    List<Component> lore = List.of(messages.component(player, session.ready()
        ? "ui-order-builder-click-confirm" : "ui-order-builder-incomplete"));
    ExchangeMenuIcons.add(page, player.getUniqueId(), new IconBuilder(
        ExchangeMenuPlatform.stack().of(session.ready() ? "LIME_CONCRETE" : "GRAY_DYE", 1)
            .customName(messages.component(player, "ui-order-builder-confirm")).lore(lore))
        .withActions(new RunnableAction(click -> confirm(player, session)))
        .withSlot(49).build());
  }

  private void confirm(Player player, Session session) {
    if (!session.ready()) {
      player.sendMessage(messages.component(player, "ui-order-builder-incomplete"));
      return;
    }
    try {
      contexts.put(player.getUniqueId(), ExchangeMenuRequest.order(session.draft()));
    } catch (IllegalArgumentException failure) {
      player.sendMessage(messages.component(player, "ui-order-builder-invalid"));
      return;
    }
    MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ORDER_CONFIRM.page(),
        ExchangeMenuPlatform.menuPlayer(player));
  }

  private void openPage(Player player) {
    MenuManager.instance().open(ExchangeMenu.NAME, ExchangeMenuPage.ORDER_ENTRY.page(),
        ExchangeMenuPlatform.menuPlayer(player));
  }

  private static String formatPrice(BigDecimal value) {
    return value == null ? "-" : value.stripTrailingZeros().toPlainString();
  }

  private static long parseQuantity(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("quantity is required");
    }
    try {
      long value = Long.parseLong(raw.trim());
      if (value <= 0) throw new IllegalArgumentException("quantity must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("quantity is invalid", invalid);
    }
  }

  private static BigDecimal parsePrice(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("price is required");
    }
    try {
      BigDecimal value = new BigDecimal(raw.trim());
      if (value.signum() <= 0) throw new IllegalArgumentException("price must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("price is invalid", invalid);
    }
  }

  private static String invalidKey(OrderType type) {
    return type == OrderType.LIMIT ? "ui-order-builder-price-invalid"
        : "ui-order-builder-protection-invalid";
  }

  /** Mutable state for one player's order draft. */
  static final class Session {
    private final UUID requestId;
    private final UUID accountId;
    private final MarketRow market;
    private OrderSide side;
    private OrderType type;
    private long quantity = 1;
    private BigDecimal priceValue;
    private BigDecimal boundary;

    private Session(UUID requestId, UUID accountId, MarketRow market) {
      this.requestId = Objects.requireNonNull(requestId, "requestId");
      this.accountId = Objects.requireNonNull(accountId, "accountId");
      this.market = Objects.requireNonNull(market, "market");
    }

    static Session initial(UUID requestId, UUID accountId, MarketRow market, OrderSide side,
                           OrderType type) {
      Session session = new Session(requestId, accountId, market);
      session.quantity(1L);
      session.switchTo(side, type);
      return session;
    }

    static BigDecimal reference(MarketRow market, OrderSide side) {
      BigDecimal best = side == OrderSide.BUY ? market.bestAsk() : market.bestBid();
      return best == null || best.signum() <= 0 ? lastPrice(market) : best;
    }

    private static BigDecimal lastPrice(MarketRow market) {
      return market.lastPrice() == null || market.lastPrice().signum() <= 0 ? null : market.lastPrice();
    }

    MarketRow market() {
      return market;
    }

    OrderSide side() {
      return side;
    }

    OrderType type() {
      return type;
    }

    long quantity() {
      return quantity;
    }

    static OrderSide referenceSide(OrderSide side) {
      return side == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    }

    static BigDecimal permille(BigDecimal reference, int permille) {
      if (reference == null || reference.signum() <= 0 || permille <= 0) {
        throw new IllegalArgumentException("reference price and permille are required");
      }
      return reference.multiply(BigDecimal.valueOf(permille))
          .divide(BigDecimal.valueOf(1_000L), reference.scale(), RoundingMode.HALF_UP)
          .stripTrailingZeros();
    }

    static String percent(int permille) {
      if (permille == 1_000) return "100%";
      return permille < 1_000 ? BigDecimal.valueOf(permille, 1).toPlainString() + "%"
          : new BigDecimal(permille).movePointLeft(1).stripTrailingZeros().toPlainString() + "%";
    }

    void switchTo(OrderSide newSide, OrderType newType) {
      this.side = Objects.requireNonNull(newSide, "side");
      this.type = Objects.requireNonNull(newType, "type");
      BigDecimal value = reference(market, side);
      if (value == null) {
        return;
      }
      int permille = newType == OrderType.LIMIT ? 1_000 : (side == OrderSide.BUY ? 1_050 : 950);
      price(permille(value, permille));
    }

    void quantity(long value) {
      if (value <= 0) throw new IllegalArgumentException("quantity must be positive");
      this.quantity = value;
    }

    void price(BigDecimal value) {
      if (value == null || value.signum() <= 0) {
        throw new IllegalArgumentException("price must be positive");
      }
      this.priceValue = value;
    }

    BigDecimal price() {
      return this.priceValue;
    }

    boolean priceMatches(BigDecimal value) {
      return this.priceValue != null && value != null
          && this.priceValue.compareTo(value) == 0;
    }

    String priceKey() {
      return type == OrderType.LIMIT ? "ui-order-builder-limit-price"
          : "ui-order-builder-boundary";
    }

    boolean ready() {
      return quantity > 0 && priceValue != null && priceValue.signum() > 0;
    }

    BigDecimal estimate() {
      return quantity <= 0 || priceValue == null || priceValue.signum() <= 0
          ? BigDecimal.ZERO
          : priceValue.multiply(BigDecimal.valueOf(quantity));
    }

    ExchangeMenuRequest.OrderDraft draft() {
      return new ExchangeMenuRequest.OrderDraft(requestId, accountId, market.marketId(), side,
          type, type == OrderType.LIMIT ? priceValue : null,
          type == OrderType.MARKET ? priceValue : null,
          quantity);
    }
  }
}
