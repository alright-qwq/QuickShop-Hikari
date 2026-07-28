package com.ghostchu.quickshop.addon.exchange.command;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ExchangeCommandRouter {
  private final Supplier<UUID> requestIds;
  private final AdminCommandRouter administration;

  public ExchangeCommandRouter(Supplier<UUID> requestIds) {
    this(requestIds, null);
  }

  public ExchangeCommandRouter(Supplier<UUID> requestIds, AdminCommandRouter administration) {
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.administration = administration;
  }

  public void execute(CommandActor actor, String[] args) {
    if (args.length > 0 && "admin".equalsIgnoreCase(args[0])) {
      if (administration == null) {
        actor.message("admin-command-invalid");
      } else {
        administration.execute(actor, java.util.Arrays.copyOfRange(args, 1, args.length));
      }
      return;
    }
    if (args.length == 0 || "open".equalsIgnoreCase(args[0])) {
      actor.openMenu("markets", 0);
      return;
    }
    if (args.length >= 2 && "order".equalsIgnoreCase(args[0])) {
      String permission = "market".equalsIgnoreCase(args[1])
          ? "quickshop.exchange.order.market" : "quickshop.exchange.order.limit";
      if (!actor.hasPermission(permission)) {
        actor.message("permission-denied");
        return;
      }
      actor.message("request-accepted", requestIds.get());
      actor.openMenu("order-confirm", 0);
      return;
    }
    actor.openMenu(args[0], 0);
  }
}
