package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Coordinates machine-verifiable inventory evidence before reviewed custody is settled. */
public final class TransferReviewCoordinator {
  private final AdminExchangeService administration;
  private final InventoryGateway inventory;
  private final WriteExecutor writes;

  public TransferReviewCoordinator(
      AdminExchangeService administration, InventoryGateway inventory, WriteExecutor writes) {
    this.administration = Objects.requireNonNull(administration, "administration");
    this.inventory = Objects.requireNonNull(inventory, "inventory");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  public CompletableFuture<TransferRecord> resolve(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision,
      String operatorEvidence) {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(decision, "decision");
    final TransferRecord review;
    try {
      TransferRecord duplicate = administration.resolvedReviewRequest(
          actorId, requestId, transferId, decision);
      if (duplicate != null) {
        return CompletableFuture.completedFuture(duplicate);
      }
      review = administration.transferReview(transferId);
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
    boolean withdrawalFailure = review.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE;
    boolean cleanupRequired = review.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        || review.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE;
    if (!withdrawalFailure && !cleanupRequired) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "review decision does not require item marker verification"));
    }
    CompletableFuture<Long> observation = observeMarkedQuantity(review);
    if (withdrawalFailure) {
      return observation.thenCompose(marked -> {
        if (marked != 0) {
          return CompletableFuture.failedFuture(new IllegalStateException(
              "marked item delivery still exists"));
        }
        return executeWrite(() -> administration.resolveVerifiedItemWithdrawalFailure(
            actorId, requestId, transferId, marked, operatorEvidence));
      });
    }
    return observation.thenCompose(markedBefore -> {
      long expected = review.amount().longValueExact();
      if (markedBefore != expected) {
        return CompletableFuture.<Long>failedFuture(new IllegalStateException(
            markedBefore < expected
                ? "marked item custody is incomplete"
                : "marked item custody is inconsistent"));
      }
      return inventory.clearMarker(review.accountId(), review.transferId())
          .handle((result, cleanupFailure) -> {
            if (cleanupFailure != null || result != com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS) {
              throw new CompletionException(new IllegalStateException(
                  "inventory marker cleanup is unavailable", cleanupFailure));
            }
            return markedBefore;
          });
    }).thenCompose(markedBefore -> observeMarkedQuantity(review)
        .thenCompose(markedAfter -> {
          if (markedAfter != 0) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "marked items remain after cleanup"));
          }
          return executeWrite(() -> administration.resolveVerifiedItemMarkerCleanup(
              actorId, requestId, transferId, decision, markedBefore, markedAfter,
              operatorEvidence));
        }));
  }

  private CompletableFuture<Long> observeMarkedQuantity(TransferRecord review) {
    return inventory.markedQuantity(review.accountId(), review.transferId())
        .handle((marked, observationFailure) -> {
          if (observationFailure != null) {
            throw new CompletionException(new IllegalStateException(
                "inventory marker observation is unavailable", observationFailure));
          }
          if (marked == null || marked < 0) {
            throw new CompletionException(new IllegalStateException(
                "inventory marker observation is unavailable"));
          }
          return marked;
        });
  }

  private CompletableFuture<TransferRecord> executeWrite(CheckedSupplier work) {
    try {
      return writes.execute(work);
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  @FunctionalInterface
  public interface WriteExecutor {
    CompletableFuture<TransferRecord> execute(CheckedSupplier work) throws Exception;
  }

  @FunctionalInterface
  public interface CheckedSupplier {
    TransferRecord get() throws Exception;
  }
}
