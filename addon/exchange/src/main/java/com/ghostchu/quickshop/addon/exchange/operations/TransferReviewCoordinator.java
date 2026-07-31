package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.transfer.InventoryGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Coordinates machine-verifiable inventory evidence before reviewed custody is settled. */
public final class TransferReviewCoordinator {
  private final AdminExchangeService administration;
  private final InventoryGateway inventory;
  private final WriteExecutor writes;
  private final Map<UUID, CompletableFuture<Void>> reviewTails = new HashMap<>();

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
    return enqueue(transferId, () -> resolveSerially(
        actorId, requestId, transferId, decision, operatorEvidence));
  }

  private synchronized CompletableFuture<TransferRecord> enqueue(
      UUID transferId, Supplier<CompletableFuture<TransferRecord>> operation) {
    CompletableFuture<TransferRecord> result = new CompletableFuture<>();
    CompletableFuture<Void> predecessor = reviewTails.getOrDefault(
        transferId, CompletableFuture.completedFuture(null));
    CompletableFuture<Void> tail = predecessor.handle((ignored, failure) -> null)
        .thenCompose(ignored -> operation.get())
        .handle((resolved, failure) -> {
          if (failure == null) {
            result.complete(resolved);
          } else {
            result.completeExceptionally(unwrap(failure));
          }
          return null;
        });
    reviewTails.put(transferId, tail);
    tail.whenComplete((ignored, failure) -> reviewFinished(transferId, tail));
    return result;
  }

  private CompletableFuture<TransferRecord> resolveSerially(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision,
      String operatorEvidence) {
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
    boolean zeroMarkerSettlement = review.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE
        || review.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS;
    boolean cleanupRequired = review.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        || review.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE;
    if (!zeroMarkerSettlement && !cleanupRequired) {
      return CompletableFuture.failedFuture(new IllegalStateException(
          "review decision does not require item marker verification"));
    }
    CompletableFuture<Long> observation = observeMarkedQuantity(review);
    if (zeroMarkerSettlement) {
      return observation.thenCompose(marked -> {
        if (marked != 0) {
          return CompletableFuture.failedFuture(new IllegalStateException(
              review.type() == TransferType.ITEM_WITHDRAWAL
                  ? "marked item delivery still exists"
                  : "marked item deposit still exists"));
        }
        return executeWrite(() -> review.type() == TransferType.ITEM_WITHDRAWAL
            ? administration.resolveVerifiedItemWithdrawalFailure(
                actorId, requestId, transferId, marked, operatorEvidence)
            : administration.resolveVerifiedItemDepositSuccess(
                actorId, requestId, transferId, marked, operatorEvidence));
      });
    }
    return observation.thenCompose(markedBefore -> {
      long expected = review.amount().longValueExact();
      if (markedBefore != expected) {
        return CompletableFuture.<TransferRecord>failedFuture(new IllegalStateException(
            markedBefore < expected
                ? "marked item custody is incomplete"
                : "marked item custody is inconsistent"));
      }
      return executeWrite(() -> administration.claimVerifiedItemMarkerCleanup(
          actorId, requestId, transferId, decision, markedBefore, operatorEvidence));
    }).thenCompose(claimed -> completeClaimed(
        claimed, administration.claimedItemReview(claimed)));
  }

  public CompletableFuture<TransferRecord> recoverClaimed(TransferRecord claimed) {
    Objects.requireNonNull(claimed, "claimed");
    return enqueue(claimed.transferId(), () -> recoverClaimedSerially(claimed));
  }

  private CompletableFuture<TransferRecord> recoverClaimedSerially(TransferRecord claimed) {
    final AdminExchangeService.ItemReviewClaim claim;
    try {
      claim = administration.claimedItemReview(claimed);
    } catch (RuntimeException failure) {
      return CompletableFuture.failedFuture(failure);
    }
    return observeMarkedQuantity(claimed).thenCompose(marked -> {
      if (marked != 0 && marked != claim.markedQuantity()) {
        return CompletableFuture.failedFuture(new IllegalStateException(
            "marked item custody does not match persisted review claim"));
      }
      if (marked == 0) {
        return settleClaimed(claimed, claim, 0);
      }
      return completeClaimed(claimed, claim);
    });
  }

  private CompletableFuture<TransferRecord> completeClaimed(
      TransferRecord claimed, AdminExchangeService.ItemReviewClaim claim) {
    return clearMarker(claimed).thenCompose(markedAfter -> settleClaimed(
        claimed, claim, markedAfter));
  }

  private CompletableFuture<TransferRecord> settleClaimed(
      TransferRecord claimed, AdminExchangeService.ItemReviewClaim claim, long markedAfter) {
    return executeWrite(() -> administration.resolveClaimedItemMarkerCleanup(
        claim.actorId(), claim.requestId(), claimed.transferId(), claim.decision(),
        claim.markedQuantity(), markedAfter, claim.operatorEvidence()));
  }

  private CompletableFuture<Long> clearMarker(TransferRecord review) {
    return inventory.clearMarker(review.accountId(), review.transferId())
        .handle((result, cleanupFailure) -> {
          if (cleanupFailure != null
              || result != com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult.SUCCESS) {
            throw new CompletionException(new IllegalStateException(
                "inventory marker cleanup is unavailable", cleanupFailure));
          }
          return null;
        })
        .thenCompose(ignored -> observeMarkedQuantity(review));
  }

  private synchronized void reviewFinished(UUID transferId, CompletableFuture<Void> tail) {
    reviewTails.remove(transferId, tail);
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
        || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
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
