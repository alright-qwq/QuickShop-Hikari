package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Parses privileged exchange commands and delegates all mutations to audited services. */
public final class AdminCommandRouter {
  private final AdminExchangeService administration;
  private final Supplier<UUID> requestIds;
  private final WriteExecutor writes;

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds) {
    this(administration, requestIds, work -> {
      work.run();
      return true;
    });
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes) {
    this.administration = Objects.requireNonNull(administration, "administration");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  public void execute(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null || args.length < 4 || !"order".equalsIgnoreCase(args[0])
        || !"cancel".equalsIgnoreCase(args[1])) {
      actor.message("admin-command-invalid");
      return;
    }
    if (!actor.hasPermission("quickshop.exchange.admin.orders")) {
      actor.message("permission-denied");
      return;
    }
    UUID orderId;
    try {
      orderId = UUID.fromString(args[2]);
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
      return;
    }
    String reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
    try {
      boolean completed = writes.execute(() ->
          administration.forceCancel(actor.accountId(), requestIds.get(), orderId, reason));
      actor.message(completed ? "request-accepted" : "admin-command-failed");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  @FunctionalInterface
  public interface WriteExecutor {
    boolean execute(CheckedWork work) throws Exception;
  }

  @FunctionalInterface
  public interface CheckedWork {
    void run() throws Exception;
  }
}
