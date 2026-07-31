package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.display.MarketChartDimensions;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartMode;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.operations.ReviewDecision;
import com.ghostchu.quickshop.addon.exchange.operations.TransferReviewCoordinator;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Parses privileged exchange commands and delegates all mutations to audited services. */
public final class AdminCommandRouter {
  private final AdminExchangeService administration;
  private final Supplier<UUID> requestIds;
  private final WriteExecutor writes;
  private final TransferReviewCoordinator itemReviews;
  private final DisplayCommands displays;
  private final HandbookCommands handbooks;

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds) {
    this(administration, requestIds, work -> {
      work.run();
      return true;
    });
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes) {
    this(administration, requestIds, writes, null);
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes, TransferReviewCoordinator itemReviews) {
    this(administration, requestIds, writes, itemReviews, null);
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes, TransferReviewCoordinator itemReviews,
                            DisplayCommands displays) {
    this(administration, requestIds, writes, itemReviews, displays, null);
  }

  public AdminCommandRouter(AdminExchangeService administration, Supplier<UUID> requestIds,
                            WriteExecutor writes, TransferReviewCoordinator itemReviews,
                            DisplayCommands displays, HandbookCommands handbooks) {
    this.administration = Objects.requireNonNull(administration, "administration");
    this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.itemReviews = itemReviews;
    this.displays = displays;
    this.handbooks = handbooks;
  }

  public void execute(CommandActor actor, String[] args) {
    Objects.requireNonNull(actor, "actor");
    if (args == null || args.length < 2) {
      actor.message("admin-command-invalid");
      return;
    }
    if ("audit".equalsIgnoreCase(args[0])) {
      audit(actor, args);
      return;
    }
    if ("transfer".equalsIgnoreCase(args[0])) {
      transferReview(actor, args);
      return;
    }
    if ("display".equalsIgnoreCase(args[0])) {
      display(actor, args);
      return;
    }
    if ("book".equalsIgnoreCase(args[0])) {
      handbook(actor, args);
      return;
    }
    if (args.length < 4) {
      actor.message("admin-command-invalid");
      return;
    }
    if ("order".equalsIgnoreCase(args[0]) && "cancel".equalsIgnoreCase(args[1])) {
      cancelOrder(actor, args);
      return;
    }
    if ("market".equalsIgnoreCase(args[0])
        && ("pause".equalsIgnoreCase(args[1]) || "resume".equalsIgnoreCase(args[1]))) {
      changeMarketStatus(actor, args);
      return;
    }
    actor.message("admin-command-invalid");
  }

  private void handbook(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.handbook")) {
      actor.message("permission-denied");
      return;
    }
    if (args.length != 3 || !"give".equalsIgnoreCase(args[1])) {
      actor.message("admin-command-invalid");
      return;
    }
    if (handbooks == null) {
      actor.message("admin-command-failed");
      return;
    }
    try {
      handbooks.give(actor, args[2]);
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void display(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.display")) {
      actor.message("permission-denied");
      return;
    }
    if (!actor.isPlayer()) {
      actor.message("display-player-only");
      return;
    }
    if (displays == null) {
      actor.message("admin-command-failed");
      return;
    }
    try {
      if (args.length >= 3 && "map".equalsIgnoreCase(args[1])) {
        displayMap(actor, args);
        return;
      }
      if (args.length >= 3 && "sign".equalsIgnoreCase(args[1])) {
        displaySign(actor, args);
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void displayMap(CommandActor actor, String[] args) throws Exception {
    String operation = args[2].toLowerCase(java.util.Locale.ROOT);
    switch (operation) {
      case "create" -> {
        if (args.length < 4 || args.length > 7) {
          throw new IllegalArgumentException("invalid map create syntax");
        }
        MarketChartDimensions dimensions = args.length >= 5
            ? MarketChartDimensions.parse(args[4]) : new MarketChartDimensions(2, 1);
        MarketChartMode mode = args.length >= 6
            ? MarketChartMode.parse(args[5]) : MarketChartMode.KLINE;
        MarketChartPeriod period = args.length >= 7
            ? MarketChartPeriod.parse(args[6]) : MarketChartPeriod.ONE_DAY;
        displays.createMap(actor, args[3], dimensions, mode, period);
      }
      case "mode" -> {
        if (args.length != 4) throw new IllegalArgumentException("mode is required");
        displays.mapMode(actor, MarketChartMode.parse(args[3]));
      }
      case "period" -> {
        if (args.length != 4) throw new IllegalArgumentException("period is required");
        displays.mapPeriod(actor, MarketChartPeriod.parse(args[3]));
      }
      case "refresh" -> {
        if (args.length != 3) throw new IllegalArgumentException("unexpected refresh arguments");
        displays.refreshMap(actor);
      }
      case "remove" -> {
        if (args.length != 3) throw new IllegalArgumentException("unexpected remove arguments");
        displays.removeMap(actor);
      }
      default -> throw new IllegalArgumentException("unknown map operation");
    }
  }

  private void displaySign(CommandActor actor, String[] args) throws Exception {
    String operation = args[2].toLowerCase(java.util.Locale.ROOT);
    switch (operation) {
      case "bind" -> {
        if (args.length != 4) throw new IllegalArgumentException("market is required");
        displays.bindSign(actor, args[3]);
      }
      case "refresh" -> {
        if (args.length != 3) throw new IllegalArgumentException("unexpected refresh arguments");
        displays.refreshSign(actor);
      }
      case "remove" -> {
        if (args.length != 3) throw new IllegalArgumentException("unexpected remove arguments");
        displays.removeSign(actor);
      }
      default -> throw new IllegalArgumentException("unknown sign operation");
    }
  }

  private void audit(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.audit")) {
      actor.message("permission-denied");
      return;
    }
    try {
      if (args.length == 2 && "reconcile".equalsIgnoreCase(args[1])) {
        executeReconciliation(actor);
        return;
      }
      if (args.length == 4 && "export".equalsIgnoreCase(args[1])) {
        java.time.Instant from = parseInstant(args[2]);
        java.time.Instant to = parseInstant(args[3]);
        java.nio.file.Path exported = administration.exportAudit(from, to);
        actor.message("admin-audit-exported", exported.getFileName().toString());
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void executeReconciliation(CommandActor actor) {
    try {
      java.util.concurrent.atomic.AtomicReference<
          com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport> report =
          new java.util.concurrent.atomic.AtomicReference<>();
      UUID requestId = requestIds.get();
      boolean completed = writes.execute(
          () -> report.set(administration.reconcile(actor.accountId(), requestId)));
      if (!completed || report.get() == null) {
        actor.message("admin-command-failed");
        return;
      }
      actor.message(report.get().balanced()
          ? "admin-reconciliation-balanced" : "admin-reconciliation-difference");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void transferReview(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.recovery")) {
      actor.message("permission-denied");
      return;
    }
    if (args.length < 3 || !"review".equalsIgnoreCase(args[1])) {
      actor.message("admin-command-invalid");
      return;
    }
    try {
      if (args.length == 3 && "list".equalsIgnoreCase(args[2])) {
        String summary = administration.pendingTransferReviews().stream()
            .map(AdminCommandRouter::transferSummary)
            .collect(java.util.stream.Collectors.joining("\n"));
        actor.message("admin-transfer-review-list", summary);
        return;
      }
      if (args.length == 4 && "show".equalsIgnoreCase(args[2])) {
        actor.message("admin-transfer-review-detail",
            transferSummary(administration.transferReview(UUID.fromString(args[3]))));
        return;
      }
      if (args.length >= 6 && "resolve".equalsIgnoreCase(args[2])) {
        UUID transferId = UUID.fromString(args[3]);
        ReviewDecision decision = switch (args[4].toLowerCase(java.util.Locale.ROOT)) {
          case "success" -> ReviewDecision.CONFIRM_EXTERNAL_SUCCESS;
          case "failure" -> ReviewDecision.CONFIRM_EXTERNAL_FAILURE;
          default -> throw new IllegalArgumentException("unknown review decision");
        };
        String evidence = String.join(" ", java.util.Arrays.copyOfRange(args, 5, args.length));
        TransferRecord review = administration.transferReview(transferId);
        boolean machineVerifiedItemReview = review.type()
            == com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType.ITEM_WITHDRAWAL
            || review.type()
            == com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType.ITEM_DEPOSIT;
        if (machineVerifiedItemReview) {
          executeItemReview(actor, transferId, decision, evidence);
        } else {
          executeWrite(actor, () -> administration.resolveReview(
              actor.accountId(), requestIds.get(), transferId, decision, evidence));
        }
        return;
      }
      actor.message("admin-command-invalid");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private void executeItemReview(
      CommandActor actor, UUID transferId, ReviewDecision decision, String evidence) {
    if (itemReviews == null) {
      actor.message("admin-command-failed");
      return;
    }
    actor.message("request-accepted");
    itemReviews.resolve(actor.accountId(), requestIds.get(), transferId, decision, evidence)
        .whenComplete((resolved, failure) -> {
          try {
            actor.dispatchCompletion(() -> actor.message(
                failure == null && resolved != null
                    ? "request-accepted" : "admin-command-failed"));
          } catch (RuntimeException ignored) {
            // The actor became unavailable before completion could be reported.
          }
        });
  }

  private static String transferSummary(TransferRecord transfer) {
    return transfer.transferId() + " " + transfer.type() + " " + transfer.status()
        + " account=" + transfer.accountId() + " asset=" + transfer.assetId()
        + " amount=" + transfer.amount().toPlainString()
        + " reason=" + Objects.toString(transfer.failureReason(), "-");
  }

  private static java.time.Instant parseInstant(String value) {
    try {
      return java.time.Instant.ofEpochSecond(Long.parseLong(value));
    } catch (NumberFormatException notEpoch) {
      return java.time.Instant.parse(value);
    }
  }

  private void cancelOrder(CommandActor actor, String[] args) {
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
    String reason = reason(args);
    executeWrite(actor, () ->
        administration.forceCancel(actor.accountId(), requestIds.get(), orderId, reason));
  }

  private void changeMarketStatus(CommandActor actor, String[] args) {
    if (!actor.hasPermission("quickshop.exchange.admin.market")) {
      actor.message("permission-denied");
      return;
    }
    String operation = args[1].toLowerCase(java.util.Locale.ROOT);
    String marketId = args[2];
    String reason = reason(args);
    executeWrite(actor, () -> {
      UUID requestId = requestIds.get();
      if ("pause".equals(operation)) {
        administration.pauseMarket(actor.accountId(), requestId, marketId, reason);
      } else {
        administration.resumeMarket(actor.accountId(), requestId, marketId, reason);
      }
    });
  }

  private void executeWrite(CommandActor actor, CheckedWork work) {
    try {
      boolean completed = writes.execute(work);
      actor.message(completed ? "request-accepted" : "admin-command-failed");
    } catch (IllegalArgumentException invalid) {
      actor.message("admin-command-invalid");
    } catch (Exception failure) {
      actor.message("admin-command-failed");
    }
  }

  private static String reason(String[] args) {
    return String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
  }

  public interface DisplayCommands {
    void createMap(CommandActor actor, String marketId, MarketChartDimensions dimensions,
                   MarketChartMode mode, MarketChartPeriod period) throws Exception;

    void mapMode(CommandActor actor, MarketChartMode mode) throws Exception;

    void mapPeriod(CommandActor actor, MarketChartPeriod period) throws Exception;

    void refreshMap(CommandActor actor) throws Exception;

    void removeMap(CommandActor actor) throws Exception;

    void bindSign(CommandActor actor, String marketId) throws Exception;

    void refreshSign(CommandActor actor) throws Exception;

    void removeSign(CommandActor actor) throws Exception;
  }

  @FunctionalInterface
  public interface HandbookCommands {
    void give(CommandActor actor, String playerName) throws Exception;
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
