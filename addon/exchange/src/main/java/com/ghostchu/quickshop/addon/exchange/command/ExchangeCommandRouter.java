package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.OrderDraft;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.TransferDraft;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest.TransferKind;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ExchangeCommandRouter {
  private final Supplier<UUID> requestIds;
  private final AdminCommandRouter administration;
  private final RolloutPolicy rollout;

  public ExchangeCommandRouter(Supplier<UUID> requestIds) {
    this(requestIds, null, RolloutPolicy.DISABLED);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration) {
    this(requestIds, administration, RolloutPolicy.DISABLED);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration,
                               RolloutPolicy rollout) {
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.administration = administration;
    this.rollout = Objects.requireNonNull(rollout, "rollout");
  }

  public void execute(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null) {
      invalid(actor);
      return;
    }
    if (args.length > 0 && "admin".equalsIgnoreCase(args[0])) {
      if (args.length == 1) {
        if (!hasAnyAdminPermission(actor)) {
          actor.message("permission-denied");
          return;
        }
        actor.openMenu(ExchangeMenuRequest.page("admin"));
        return;
      }
      if (administration == null) {
        actor.message("admin-command-invalid");
      } else {
        administration.execute(actor, java.util.Arrays.copyOfRange(args, 1, args.length));
      }
      return;
    }
    if (!rollout.allows(actor.accountId())) {
      actor.message("rollout-not-allowed");
      return;
    }
    if (args.length == 0 || "open".equalsIgnoreCase(args[0])) {
      if (args.length > 1 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      actor.openMenu(ExchangeMenuRequest.page("markets"));
      return;
    }
    if ("book".equalsIgnoreCase(args[0])) {
      if (args.length != 1) {
        invalid(actor);
        return;
      }
      if (!allowed(actor, "quickshop.exchange.use")) {
        return;
      }
      actor.claimHandbook();
      return;
    }
    if ("market".equalsIgnoreCase(args[0])) {
      if (args.length != 2 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      try {
        actor.openMenu(ExchangeMenuRequest.market(args[1]));
      } catch (IllegalArgumentException invalid) {
        invalid(actor);
      }
      return;
    }
    if ("order".equalsIgnoreCase(args[0])) {
      routeOrder(actor, args);
      return;
    }
    if ("cancel".equalsIgnoreCase(args[0])) {
      routeCancel(actor, args);
      return;
    }
    if ("deposit".equalsIgnoreCase(args[0]) || "withdraw".equalsIgnoreCase(args[0])) {
      routeTransfer(actor, args);
      return;
    }
    if ("orders".equalsIgnoreCase(args[0]) || "assets".equalsIgnoreCase(args[0])
        || "history".equalsIgnoreCase(args[0])) {
      if (args.length != 1 || !allowed(actor, "quickshop.exchange.use")) {
        invalid(actor);
        return;
      }
      actor.openMenu(ExchangeMenuRequest.page(args[0]));
      return;
    }
    invalid(actor);
  }

  private void routeOrder(CommandActor actor, String[] args) {
    if (args.length < 5 || args.length > 6) {
      invalid(actor);
      return;
    }
    OrderType type;
    OrderSide side;
    try {
      type = parseType(args[1]);
      side = parseSide(args[2]);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
      return;
    }
    String permission = type == OrderType.MARKET
        ? "quickshop.exchange.order.market" : "quickshop.exchange.order.limit";
    if (!allowed(actor, permission)) {
      return;
    }
    if (args.length != 6) {
      invalid(actor);
      return;
    }
    try {
      BigDecimal price = type == OrderType.LIMIT ? positiveDecimal(args[4], "price") : null;
      long quantity = positiveLong(args[type == OrderType.LIMIT ? 5 : 4], "quantity");
      BigDecimal boundary = null;
      if (type == OrderType.MARKET) {
        boundary = positiveDecimal(args[5], "slippage boundary");
      }
      ExchangeMenuRequest request = ExchangeMenuRequest.order(new OrderDraft(
          requestIds.get(), actor.accountId(), args[3], side, type, price, boundary, quantity));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private void routeCancel(CommandActor actor, String[] args) {
    if (args.length != 2) {
      invalid(actor);
      return;
    }
    if (!allowed(actor, "quickshop.exchange.order.cancel")) {
      return;
    }
    try {
      ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
          requestIds.get(), actor.accountId(), UUID.fromString(args[1]));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private void routeTransfer(CommandActor actor, String[] args) {
    if (args.length != 4) {
      invalid(actor);
      return;
    }
    String verb = args[0].toLowerCase(java.util.Locale.ROOT);
    if (!allowed(actor, "quickshop.exchange." + verb)) {
      return;
    }
    try {
      boolean money = "money".equalsIgnoreCase(args[1]);
      TransferKind kind;
      BigDecimal amount = null;
      long quantity = 0;
      String assetId = args[2];
      String marketId = money ? null : args[2];
      if (money) {
        amount = positiveDecimal(args[3], "amount");
        kind = "deposit".equals(verb) ? TransferKind.MONEY_DEPOSIT
            : TransferKind.MONEY_WITHDRAWAL;
        assetId = args[2];
      } else {
        quantity = positiveLong(args[3], "quantity");
        kind = "deposit".equals(verb) ? TransferKind.ITEM_DEPOSIT
            : TransferKind.ITEM_WITHDRAWAL;
        assetId = marketId;
      }
      ExchangeMenuRequest request = ExchangeMenuRequest.transfer(new TransferDraft(
          requestIds.get(), actor.accountId(), kind, assetId, amount, quantity, marketId));
      actor.message("request-ready", request.requestId());
      actor.openMenu(request);
    } catch (IllegalArgumentException invalid) {
      invalid(actor);
    }
  }

  private static OrderType parseType(String raw) {
    return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
      case "limit" -> OrderType.LIMIT;
      case "market" -> OrderType.MARKET;
      default -> throw new IllegalArgumentException("unknown order type");
    };
  }

  private static OrderSide parseSide(String raw) {
    return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
      case "buy" -> OrderSide.BUY;
      case "sell" -> OrderSide.SELL;
      default -> throw new IllegalArgumentException("unknown order side");
    };
  }

  private static BigDecimal positiveDecimal(String raw, String name) {
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid " + name, invalid);
    }
  }

  private static long positiveLong(String raw, String name) {
    try {
      long value = Long.parseLong(raw);
      if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("invalid " + name, invalid);
    }
  }

  private static boolean allowed(CommandActor actor, String permission) {
    if (actor.hasPermission(permission)) return true;
    actor.message("permission-denied");
    return false;
  }

  private static boolean hasAnyAdminPermission(CommandActor actor) {
    return actor.hasPermission("quickshop.exchange.admin.market")
        || actor.hasPermission("quickshop.exchange.admin.orders")
        || actor.hasPermission("quickshop.exchange.admin.recovery")
        || actor.hasPermission("quickshop.exchange.admin.audit")
        || actor.hasPermission("quickshop.exchange.admin.display")
        || actor.hasPermission("quickshop.exchange.admin.handbook");
  }

  private static void invalid(CommandActor actor) {
    actor.message("command-invalid");
  }

  /** Returns only known subcommands and argument choices. */
  public List<String> tabComplete(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null || args.length == 0) {
      return List.of("open", "book", "market", "order", "cancel", "deposit", "withdraw", "orders",
          "assets", "history", "admin");
    }
    if (args.length == 1) {
      String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
      return List.of("open", "book", "market", "order", "cancel", "deposit", "withdraw", "orders",
          "assets", "history", "admin").stream()
          .filter(value -> value.startsWith(prefix)).toList();
    }
    return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
      case "order" -> args.length == 2 ? List.of("limit", "market")
          : args.length == 3 ? List.of("buy", "sell") : List.of();
      case "deposit", "withdraw" -> args.length == 2 ? List.of("money", "item") : List.of();
      case "admin" -> completeAdmin(args);
      default -> List.of();
    };
  }

  private static List<String> completeAdmin(String[] args) {
    if (args.length == 2) {
      return List.of("market", "order", "transfer", "audit", "display", "book");
    }
    if (args.length == 3 && "book".equalsIgnoreCase(args[1])) {
      return List.of("give");
    }
    if (args.length == 3 && "transfer".equalsIgnoreCase(args[1])) {
      return List.of("review");
    }
    if (args.length == 4 && "transfer".equalsIgnoreCase(args[1])
        && "review".equalsIgnoreCase(args[2])) {
      return List.of("list", "show", "resolve");
    }
    if (args.length == 3 && "display".equalsIgnoreCase(args[1])) {
      return List.of("map", "sign");
    }
    if (args.length == 4 && "display".equalsIgnoreCase(args[1])) {
      if ("map".equalsIgnoreCase(args[2])) {
        return List.of("create", "mode", "period", "refresh", "remove");
      }
      if ("sign".equalsIgnoreCase(args[2])) {
        return List.of("bind", "refresh", "remove");
      }
    }
    if (args.length == 6 && "display".equalsIgnoreCase(args[1])
        && "map".equalsIgnoreCase(args[2]) && "create".equalsIgnoreCase(args[3])) {
      return List.of("1x1", "2x1", "2x2");
    }
    if (args.length == 7 && "display".equalsIgnoreCase(args[1])
        && "map".equalsIgnoreCase(args[2]) && "create".equalsIgnoreCase(args[3])) {
      return List.of("kline", "line");
    }
    if (args.length == 8 && "display".equalsIgnoreCase(args[1])
        && "map".equalsIgnoreCase(args[2]) && "create".equalsIgnoreCase(args[3])) {
      return List.of("1h", "6h", "24h", "7d");
    }
    return List.of();
  }
}
