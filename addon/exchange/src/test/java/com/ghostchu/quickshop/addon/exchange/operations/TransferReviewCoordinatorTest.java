package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferJournals;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferReviewCoordinatorTest {
  @Test
  void releasesItemWithdrawalOnlyAfterMachineObservesNoMarkedDelivery() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, markedQuantity(0L), work -> CompletableFuture.completedFuture(work.get()));

    TransferRecord resolved = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator requested custody review").join();

    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableItems(account)).isEqualTo(3);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void duplicateRequestReturnsOriginalTerminalTransferWithoutObservingAgain() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger observations =
        new java.util.concurrent.atomic.AtomicInteger();
    InventoryGateway inventory = markedQuantity(observations, 0L);
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, inventory, work -> CompletableFuture.completedFuture(work.get()));
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();

    TransferRecord first = coordinator.resolve(
        actorId, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator ticket-001").join();
    TransferRecord duplicate = coordinator.resolve(
        actorId, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator ticket-001").join();

    assertThat(duplicate).isEqualTo(first);
    assertThat(observations).hasValue(1);
    assertThat(fixture.availableItems(account)).isEqualTo(3);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void completesRemovedItemDepositOnlyAfterMachineObservesNoMarker() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedDeposit(
        fixture, account, "inventory deposit removal result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, markedQuantity(0L), work -> CompletableFuture.completedFuture(work.get()));

    TransferRecord resolved = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator removal uncertainty ticket").join();

    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(2);
  }

  @Test
  void keepsRemovedItemDepositInReviewWhenMarkerStillExists() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedDeposit(
        fixture, account, "inventory deposit removal result unknown");
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, markedQuantity(2L), work -> CompletableFuture.completedFuture(work.get()));

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator removal uncertainty ticket").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("marked item deposit still exists");
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.availableItems(account)).isZero();
  }

  @Test
  void keepsItemWithdrawalInReviewWhenMarkedDeliveryExists() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, markedQuantity(2L), work -> CompletableFuture.completedFuture(work.get()));

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator requested custody review").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("marked item delivery still exists");
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.frozenItems(account)).isEqualTo(2);
  }

  @Test
  void keepsItemWithdrawalInReviewWhenInventoryObservationFails() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    InventoryGateway unavailable = markedQuantity(-1L);
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, unavailable, work -> CompletableFuture.completedFuture(work.get()));

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator requested custody review").join())
        .hasCauseInstanceOf(IllegalStateException.class)
        .satisfies(failure -> assertThat(failure.getCause())
            .hasMessage("inventory marker observation is unavailable")
            .hasCauseInstanceOf(IllegalStateException.class));
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
  }

  @Test
  void serialisesDifferentReviewRequestsForTheSameTransfer() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    CompletableFuture<Long> firstObservation = new CompletableFuture<>();
    java.util.concurrent.atomic.AtomicInteger observations =
        new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, markedQuantity(firstObservation, observations),
        work -> CompletableFuture.completedFuture(work.get()));

    CompletableFuture<TransferRecord> first = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator custody ticket first");
    CompletableFuture<TransferRecord> second = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator custody ticket second");

    assertThat(observations).hasValue(1);
    assertThat(second).isNotDone();
    firstObservation.complete(0L);
    assertThat(first.join().status()).isEqualTo(TransferStatus.FAILED);
    assertThatThrownBy(second::join)
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("transfer is not awaiting review: FAILED");
    assertThat(observations).hasValue(1);
  }

  @Test
  void completesItemWithdrawalBeforeUnlockingDeliveredItems() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicReference<TransferStatus> statusAtCleanup =
        new java.util.concurrent.atomic.AtomicReference<>();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.SUCCESS, 0L,
            () -> {
              try {
                statusAtCleanup.set(fixture.repository().find(
                    reviewed.transferId()).orElseThrow().status());
              } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
              }
            }),
        work -> CompletableFuture.completedFuture(work.get()));

    TransferRecord resolved = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery ticket-002").join();

    assertThat(statusAtCleanup).hasValue(TransferStatus.REVIEW_PROCESSING);
    assertThat(resolved.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void failsItemDepositOnlyAfterMarkerCleanupIsVerified() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = UUID.randomUUID();
    TransferRecord reviewed = reviewedDeposit(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.SUCCESS, 0L),
        work -> CompletableFuture.completedFuture(work.get()));

    TransferRecord resolved = coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, "operator verified deposit rollback").join();

    assertThat(resolved.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableItems(account)).isZero();
  }

  @Test
  void keepsItemCleanupReviewOpenWhenInitialMarkerCustodyIsPartial() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(1L, InventoryResult.SUCCESS, 0L), work -> {
          writes.incrementAndGet();
          return CompletableFuture.completedFuture(work.get());
        });

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery ticket-003").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("marked item custody is incomplete");
    assertThat(writes).hasValue(0);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
  }

  @Test
  void keepsItemCleanupReviewOpenWhenInitialMarkerCustodyIsExcessive() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(3L, InventoryResult.SUCCESS, 0L), work -> {
          writes.incrementAndGet();
          return CompletableFuture.completedFuture(work.get());
        });

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery ticket-003b").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("marked item custody is inconsistent");
    assertThat(writes).hasValue(0);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_REQUIRED);
  }

  @Test
  void keepsItemCleanupReviewOpenWhenCleanupLeavesMarkedItems() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.SUCCESS, 1L), work -> {
          writes.incrementAndGet();
          return CompletableFuture.completedFuture(work.get());
        });

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery ticket-004").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("marked items remain after cleanup");
    assertThat(writes).hasValue(2);
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_PROCESSING);
  }

  @Test
  void keepsItemCleanupReviewOpenWhenCleanupOutcomeIsUnknown() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.UNKNOWN, 2L),
        work -> CompletableFuture.completedFuture(work.get()));

    assertThatThrownBy(() -> coordinator.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery ticket-003").join())
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("inventory marker cleanup is unavailable");
    assertThat(fixture.repository().find(reviewed.transferId()).orElseThrow().status())
        .isEqualTo(TransferStatus.REVIEW_PROCESSING);
    assertThat(fixture.frozenItems(account)).isEqualTo(2);
  }

  @Test
  void serialisesClaimRecoveryWithAnAdministratorRetryForTheSameTransfer() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    String evidence = "operator verified delivery before recovery";
    TransferRecord claimed = admin.claimVerifiedItemMarkerCleanup(
        actorId, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, 2L, evidence);
    CompletableFuture<Long> recoveryObservation = new CompletableFuture<>();
    java.util.concurrent.atomic.AtomicInteger observations =
        new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator coordinator = new TransferReviewCoordinator(
        admin, recoveryInventory(recoveryObservation, observations),
        work -> CompletableFuture.completedFuture(work.get()));

    CompletableFuture<TransferRecord> recovery = coordinator.recoverClaimed(claimed);
    CompletableFuture<TransferRecord> retry = coordinator.resolve(
        actorId, requestId, reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, evidence);

    assertThat(observations).hasValue(1);
    assertThat(retry).isNotDone();
    recoveryObservation.complete(2L);

    assertThat(recovery.join().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(retry.join().status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(observations).hasValue(2);
  }

  @Test
  void resumesClaimedItemWithdrawalAfterRestartWhenMarkerWasAlreadyCleared() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    UUID actorId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
    TransferReviewCoordinator interrupted = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.SUCCESS, 0L), work -> {
          if (writes.incrementAndGet() == 2) {
            return CompletableFuture.failedFuture(new IllegalStateException("simulated restart"));
          }
          return CompletableFuture.completedFuture(work.get());
        });

    assertThatThrownBy(() -> interrupted.resolve(
        actorId, requestId, reviewed.transferId(), ReviewDecision.CONFIRM_EXTERNAL_SUCCESS,
        "operator verified delivery before restart").join())
        .hasRootCauseMessage("simulated restart");
    TransferRecord claimed = fixture.repository().find(reviewed.transferId()).orElseThrow();
    assertThat(claimed.status()).isEqualTo(TransferStatus.REVIEW_PROCESSING);

    TransferReviewCoordinator restarted = new TransferReviewCoordinator(
        admin, markedQuantity(0L), work -> CompletableFuture.completedFuture(work.get()));
    TransferRecord recovered = restarted.recoverClaimed(claimed).join();

    assertThat(recovered.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  @Test
  void resumesClaimedItemWithdrawalAfterRestartByRetryingMarkerCleanup() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithItems(3);
    TransferRecord reviewed = reviewedWithdrawal(fixture, account);
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()), fixture.repository());
    TransferReviewCoordinator interrupted = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.UNKNOWN, 2L),
        work -> CompletableFuture.completedFuture(work.get()));

    assertThatThrownBy(() -> interrupted.resolve(
        UUID.randomUUID(), UUID.randomUUID(), reviewed.transferId(),
        ReviewDecision.CONFIRM_EXTERNAL_SUCCESS, "operator verified delivery before restart").join())
        .hasRootCauseMessage("inventory marker cleanup is unavailable");
    TransferRecord claimed = fixture.repository().find(reviewed.transferId()).orElseThrow();

    TransferReviewCoordinator restarted = new TransferReviewCoordinator(
        admin, cleanupInventory(2L, InventoryResult.SUCCESS, 0L),
        work -> CompletableFuture.completedFuture(work.get()));
    TransferRecord recovered = restarted.recoverClaimed(claimed).join();

    assertThat(recovered.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(fixture.availableItems(account)).isEqualTo(1);
    assertThat(fixture.frozenItems(account)).isZero();
  }

  private static TransferRecord reviewedWithdrawal(ExchangeServiceFixture fixture, UUID account)
      throws Exception {
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
    return reviewed(fixture, prepared);
  }

  private static TransferRecord reviewedDeposit(ExchangeServiceFixture fixture, UUID account)
      throws Exception {
    return reviewedDeposit(fixture, account, "external result unknown");
  }

  private static TransferRecord reviewedDeposit(
      ExchangeServiceFixture fixture, UUID account, String reason) throws Exception {
    TransferRecord prepared = fixture.repository().create(TransferRecord.prepared(
        UUID.randomUUID(), UUID.randomUUID(), account, TransferType.ITEM_DEPOSIT,
        fixture.rules().marketId(), BigDecimal.valueOf(2), Instant.EPOCH));
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, reason);
  }

  private static TransferRecord reviewed(
      ExchangeServiceFixture fixture, TransferRecord prepared) throws Exception {
    TransferRecord processing = fixture.repository().transition(
        prepared.transferId(), prepared.version(), TransferStatus.PREPARED,
        TransferStatus.PROCESSING, null);
    return fixture.repository().transition(processing.transferId(), processing.version(),
        TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, "external result unknown");
  }

  private static InventoryGateway markedQuantity(long quantity) {
    return markedQuantity(new java.util.concurrent.atomic.AtomicInteger(), quantity);
  }

  private static InventoryGateway cleanupInventory(
      long beforeCleanup, InventoryResult cleanupResult, long afterCleanup) {
    return cleanupInventory(beforeCleanup, cleanupResult, afterCleanup, () -> {});
  }

  private static InventoryGateway cleanupInventory(
      long beforeCleanup, InventoryResult cleanupResult, long afterCleanup,
      Runnable beforeClear) {
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
        beforeClear.run();
        return CompletableFuture.completedFuture(cleanupResult);
      }
    };
  }

  private static InventoryGateway recoveryInventory(
      CompletableFuture<Long> firstQuantity,
      java.util.concurrent.atomic.AtomicInteger observations) {
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
        return observations.incrementAndGet() == 1
            ? firstQuantity : CompletableFuture.completedFuture(0L);
      }
      @Override public CompletableFuture<InventoryResult> clearMarker(
          UUID playerId, UUID transferId) {
        return CompletableFuture.completedFuture(InventoryResult.SUCCESS);
      }
    };
  }

  private static InventoryGateway markedQuantity(
      CompletableFuture<Long> quantity,
      java.util.concurrent.atomic.AtomicInteger observations) {
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

  private static InventoryGateway markedQuantity(
      java.util.concurrent.atomic.AtomicInteger observations, long quantity) {
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
        return quantity < 0
            ? CompletableFuture.failedFuture(new IllegalStateException("player unavailable"))
            : CompletableFuture.completedFuture(quantity);
      }
      @Override public CompletableFuture<InventoryResult> clearMarker(
          UUID playerId, UUID transferId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException());
      }
    };
  }
}
