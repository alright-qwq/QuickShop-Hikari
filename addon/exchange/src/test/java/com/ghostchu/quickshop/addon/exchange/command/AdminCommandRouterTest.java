package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartDimensions;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartMode;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.operations.TransferReviewCoordinator;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferJournals;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommandRouterTest {
  @Test
  void completesDisplayCommandSyntax() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.display");

    assertThat(router.tabComplete(actor, new String[] {"admin", "display", ""}))
        .containsExactly("map", "sign");
    assertThat(router.tabComplete(actor, new String[] {"admin", "display", "map", ""}))
        .containsExactly("create", "mode", "period", "refresh", "remove");
  }

  @Test
  void givesHandbookToNamedOnlinePlayerWithDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    java.util.concurrent.atomic.AtomicReference<String> target =
        new java.util.concurrent.atomic.AtomicReference<>();
    AdminCommandRouter router = new AdminCommandRouter(
        new AdminExchangeService(Map.of(fixture.rules().marketId(), fixture.service())),
        UUID::randomUUID,
        work -> { work.run(); return true; },
        null,
        null,
        (actor, playerName) -> {
          target.set(playerName);
          actor.message("admin-handbook-given", playerName);
        });
    Actor actor = new Actor("quickshop.exchange.admin.handbook");

    router.execute(actor, new String[] {"book", "give", "Trader"});

    assertThat(target).hasValue("Trader");
    assertThat(actor.message).isEqualTo("admin-handbook-given");
    assertThat(actor.arguments).containsExactly("Trader");
  }

  @Test
  void deniesHandbookGiveWithoutDedicatedPermissionOrProvider() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));
    java.util.concurrent.atomic.AtomicInteger gives = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter configured = new AdminCommandRouter(
        administration, UUID::randomUUID, work -> true, null, null,
        (actor, playerName) -> gives.incrementAndGet());
    Actor denied = new Actor("quickshop.exchange.admin.audit");
    Actor missingProvider = new Actor("quickshop.exchange.admin.handbook");

    configured.execute(denied, new String[] {"book", "give", "Trader"});
    new AdminCommandRouter(administration, UUID::randomUUID)
        .execute(missingProvider, new String[] {"book", "give", "Trader"});

    assertThat(denied.message).isEqualTo("permission-denied");
    assertThat(missingProvider.message).isEqualTo("admin-command-failed");
    assertThat(gives).hasValue(0);
  }

  @Test
  void completesAdministratorHandbookSyntaxAndPermissionOpensAdminPage() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.handbook");

    assertThat(router.tabComplete(actor, new String[] {"admin", ""}))
        .contains("book");
    assertThat(router.tabComplete(actor, new String[] {"admin", "book", ""}))
        .containsExactly("give");
    router.execute(actor, new String[] {"admin"});

    assertThat(actor.openedMenu).isEqualTo("admin");
  }

  @Test
  void createsMapDisplayWithDefaultsAndDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    DisplayCommands displays = new DisplayCommands();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID,
        work -> { work.run(); return true; }, null, displays);
    Actor actor = new Actor("quickshop.exchange.admin.display");

    router.execute(actor, new String[] {"display", "map", "create", fixture.rules().marketId()});

    assertThat(displays.operation).isEqualTo("map-create");
    assertThat(displays.marketId).isEqualTo(fixture.rules().marketId());
    assertThat(displays.dimensions).isEqualTo(new MarketChartDimensions(2, 1));
    assertThat(displays.mode).isEqualTo(MarketChartMode.KLINE);
    assertThat(displays.period).isEqualTo(MarketChartPeriod.ONE_DAY);
    assertThat(actor.message).isEqualTo("display-map-created");
  }

  @Test
  void deniesDisplayCommandsWithoutDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    DisplayCommands displays = new DisplayCommands();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID,
        work -> { work.run(); return true; }, null, displays);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"display", "sign", "bind", fixture.rules().marketId()});

    assertThat(displays.operation).isNull();
    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void rejectsDisplayCommandsWithoutAPlayerTarget() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    DisplayCommands displays = new DisplayCommands();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID,
        work -> { work.run(); return true; }, null, displays);
    Actor actor = new Actor("quickshop.exchange.admin.display");
    actor.player = false;

    router.execute(actor, new String[] {"display", "map", "refresh"});

    assertThat(displays.operation).isNull();
    assertThat(actor.message).isEqualTo("display-player-only");
  }

  @Test
  void cancelsAnOpenOrderWithTheDedicatedOrdersPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    OrderReceipt order = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        fixture.rules().marketId(), OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    Actor actor = new Actor("quickshop.exchange.admin.orders");
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);

    router.execute(actor, new String[] {"order", "cancel", order.orderId().toString(),
        "suspected abuse"});

    assertThat(fixture.orderStatus(order.orderId())).isEqualTo("CANCELLED");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  @Test
  void deniesOrderCancellationWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);
    Actor actor = new Actor();

    router.execute(actor, new String[] {"order", "cancel", UUID.randomUUID().toString(),
        "suspected abuse"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void pausesAndResumesMarketsWithTheDedicatedMarketPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"market", "pause", fixture.rules().marketId(),
        "scheduled maintenance"});
    String paused = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(paused).isEqualTo("PAUSED");
    assertThat(actor.message).isEqualTo("request-accepted");

    router.execute(actor, new String[] {"market", "resume", fixture.rules().marketId(),
        "maintenance completed"});
    String resumed = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(resumed).isEqualTo("OPEN");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  @Test
  void deniesMarketMutationWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.orders");

    router.execute(actor, new String[] {"market", "pause", fixture.rules().marketId(),
        "scheduled maintenance"});

    assertThat(actor.message).isEqualTo("permission-denied");
    String status = fixture.repository().inTransaction(
        tx -> tx.marketState(fixture.rules().marketId()).status().name());
    assertThat(status).isEqualTo("OPEN");
  }

  @Test
  void reconcilesAndExportsAuditWithTheDedicatedAuditPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    var directory = Files.createTempDirectory("exchange-admin-route-audit-");
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository(),
        new com.ghostchu.quickshop.addon.exchange.operations.AuditExporter(), directory);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(administration, UUID::randomUUID, work -> {
      writes.incrementAndGet();
      work.run();
      return true;
    });
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"audit", "reconcile"});
    assertThat(actor.message).isEqualTo("admin-reconciliation-balanced");
    assertThat(writes).hasValue(1);

    router.execute(actor, new String[] {"audit", "export", "0",
        Long.toString(Instant.now().plusSeconds(1).getEpochSecond())});
    assertThat(actor.message).isEqualTo("admin-audit-exported");
    assertThat(Files.list(directory)).hasSize(1);
  }

  @Test
  void deniesAuditOperationsWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.market");

    router.execute(actor, new String[] {"audit", "reconcile"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void listsAndShowsReviewedTransfersWithoutEnteringTheWriterFence() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()),
        UUID::randomUUID, work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        });
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "list"});
    assertThat(actor.message).isEqualTo("admin-transfer-review-list");
    assertThat(actor.arguments).singleElement().asString().contains(reviewed.transferId().toString());

    router.execute(actor, new String[] {"transfer", "review", "show",
        reviewed.transferId().toString()});
    assertThat(actor.message).isEqualTo("admin-transfer-review-detail");
    assertThat(actor.arguments).singleElement().asString()
        .contains(reviewed.transferId().toString(), "MONEY_DEPOSIT", "REVIEW_REQUIRED");
    assertThat(writes).hasValue(0);
  }

  @Test
  void resolvesReviewedTransferThroughWriterFenceWithRecoveryPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()),
        UUID::randomUUID, work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        });
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "resolve",
        reviewed.transferId().toString(), "success", "economy", "receipt", "provider-001"});

    assertThat(actor.message).isEqualTo("request-accepted");
    assertThat(writes).hasValue(1);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.COMPLETED);
  }

  @Test
  void resolvesItemWithdrawalFailureThroughMachineVerifiedCoordinator() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedItemWithdrawal(fixture, account);
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger markerObservations =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        administration, markedQuantity(markerObservations, 0L), work -> {
          writes.incrementAndGet();
          return CompletableFuture.completedFuture(work.get());
        });
    AdminCommandRouter router = new AdminCommandRouter(administration, UUID::randomUUID,
        work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        }, coordinator);
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "resolve",
        reviewed.transferId().toString(), "failure", "operator", "ticket-001"});

    assertThat(actor.message).isEqualTo("request-accepted");
    assertThat(markerObservations).hasValue(1);
    assertThat(writes).hasValue(1);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableItems(account)).isEqualTo(3);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void resolvesItemWithdrawalSuccessThroughMarkerCleanupCoordinator() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedItemWithdrawal(fixture, account);
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        administration, cleanupInventory(2L, 0L), work -> {
          writes.incrementAndGet();
          return CompletableFuture.completedFuture(work.get());
        });
    AdminCommandRouter router = new AdminCommandRouter(administration, UUID::randomUUID,
        work -> {
          writes.incrementAndGet();
          work.run();
          return true;
        }, coordinator);
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "resolve",
        reviewed.transferId().toString(), "success", "operator", "ticket-002"});

    assertThat(actor.message).isEqualTo("request-accepted");
    assertThat(writes).hasValue(2);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void reportsItemWithdrawalReviewFailureOnTheActorCompletionDispatcher() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedItemWithdrawal(fixture, account);
    AdminExchangeService administration = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    CompletableFuture<Long> observation = new CompletableFuture<>();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        administration, markedQuantity(observation),
        work -> CompletableFuture.completedFuture(work.get()));
    AdminCommandRouter router = new AdminCommandRouter(administration, UUID::randomUUID,
        work -> {
          work.run();
          return true;
        }, coordinator);
    Actor actor = new Actor("quickshop.exchange.admin.recovery");

    router.execute(actor, new String[] {"transfer", "review", "resolve",
        reviewed.transferId().toString(), "failure", "operator", "ticket-001"});
    assertThat(actor.message).isEqualTo("request-accepted");

    observation.completeExceptionally(new IllegalStateException("player offline"));

    assertThat(actor.message).isEqualTo("admin-command-failed");
    assertThat(actor.completionDispatches).hasValue(1);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
  }

  @Test
  void deniesTransferReviewWithoutRecoveryPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    TransferRecord reviewed = reviewedMoneyDeposit(fixture);
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository()), UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    router.execute(actor, new String[] {"transfer", "review", "show",
        reviewed.transferId().toString()});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  private static TransferRecord reviewedItemWithdrawal(
      ExchangeServiceFixture fixture, UUID account) throws Exception {
    BigDecimal quantity = BigDecimal.valueOf(2);
    TransferRecord candidate = TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), account, TransferType.ITEM_WITHDRAWAL,
        fixture.rules().marketId(), quantity, Instant.EPOCH);
    TransferRecord prepared = fixture.repository().inTransaction(tx -> {
      TransferRecord persisted = tx.createTransfer(candidate);
      tx.freezeItems(account, fixture.rules().marketId(), quantity.longValueExact());
      tx.appendJournal(TransferJournals.freezeItemWithdrawal(candidate, Instant.EPOCH));
      return persisted;
    });
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, "external result unknown");
  }

  private static InventoryGateway cleanupInventory(long beforeCleanup, long afterCleanup) {
    java.util.concurrent.atomic.AtomicInteger observations =
        new java.util.concurrent.atomic.AtomicInteger();
    return new InventoryGateway() {
      @Override public CompletableFuture<InventoryResult> markForDeposit(
          UUID playerId, ItemStack template, long amount, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<InventoryResult> removeMarked(
          UUID playerId, UUID transferId, long amount) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<InventoryResult> deliverMarked(
          UUID playerId, ItemStack template, long amount, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
        return CompletableFuture.completedFuture(
            observations.getAndIncrement() == 0 ? beforeCleanup : afterCleanup);
      }
      @Override public CompletableFuture<InventoryResult> clearMarker(
          UUID playerId, UUID transferId) {
        return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
      }
    };
  }

  private static InventoryGateway markedQuantity(
      java.util.concurrent.atomic.AtomicInteger observations, long quantity) {
    return markedQuantity(CompletableFuture.completedFuture(quantity), observations);
  }

  private static InventoryGateway markedQuantity(CompletableFuture<Long> quantity) {
    return markedQuantity(quantity, new java.util.concurrent.atomic.AtomicInteger());
  }

  private static InventoryGateway markedQuantity(
      CompletableFuture<Long> quantity, java.util.concurrent.atomic.AtomicInteger observations) {
    return new InventoryGateway() {
      @Override public CompletableFuture<InventoryResult> markForDeposit(
          UUID playerId, ItemStack template, long amount, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<InventoryResult> removeMarked(
          UUID playerId, UUID transferId, long amount) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<InventoryResult> deliverMarked(
          UUID playerId, ItemStack template, long amount, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
      @Override public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
        observations.incrementAndGet();
        return quantity;
      }
      @Override public CompletableFuture<InventoryResult> clearMarker(
          UUID playerId, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
    };
  }

  private static TransferRecord reviewedMoneyDeposit(ExchangeServiceFixture fixture)
      throws Exception {
    TransferRecord prepared = fixture.repository().create(TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TransferType.MONEY_DEPOSIT,
        fixture.rules().currencyId(), new BigDecimal("12.00"), Instant.EPOCH));
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
        "economy withdrawal result unknown");
  }

  private static final class DisplayCommands implements AdminCommandRouter.DisplayCommands {
    private String operation;
    private String marketId;
    private MarketChartDimensions dimensions;
    private MarketChartMode mode;
    private MarketChartPeriod period;

    @Override
    public void createMap(CommandActor actor, String marketId, MarketChartDimensions dimensions,
                          MarketChartMode mode, MarketChartPeriod period) {
      this.operation = "map-create";
      this.marketId = marketId;
      this.dimensions = dimensions;
      this.mode = mode;
      this.period = period;
      actor.message("display-map-created");
    }

    @Override public void mapMode(CommandActor actor, MarketChartMode mode) {
      operation = "map-mode";
    }
    @Override public void mapPeriod(CommandActor actor, MarketChartPeriod period) {
      operation = "map-period";
    }
    @Override public void refreshMap(CommandActor actor) { operation = "map-refresh"; }
    @Override public void removeMap(CommandActor actor) { operation = "map-remove"; }
    @Override public void bindSign(CommandActor actor, String marketId) { operation = "sign-bind"; }
    @Override public void refreshSign(CommandActor actor) { operation = "sign-refresh"; }
    @Override public void removeSign(CommandActor actor) { operation = "sign-remove"; }
  }

  private static final class Actor implements CommandActor {
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private Object[] arguments = new Object[0];
    private final java.util.concurrent.atomic.AtomicInteger completionDispatches =
        new java.util.concurrent.atomic.AtomicInteger();
    private boolean player = true;
    private String openedMenu;

    private Actor(String... permissions) {
      this.permissions.addAll(Set.of(permissions));
    }

    @Override public UUID accountId() { return accountId; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public boolean isPlayer() { return player; }
    @Override public void message(String key, Object... arguments) {
      message = key;
      this.arguments = arguments;
    }
    @Override public void dispatchCompletion(Runnable completion) {
      completionDispatches.incrementAndGet();
      completion.run();
    }
    @Override public void openMenu(String menuName, int page) { openedMenu = menuName; }
  }
}
