package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Backend action facade used by command and GUI adapters. */
public final class ExchangeActionService {
  private final Map<String, PersistentOrderService> markets;
  private final TransferActions transfers;

  public ExchangeActionService(Map<String, PersistentOrderService> markets,
                               MoneyTransferService money, ItemTransferService items) {
    this(markets, new TransferActions() {
      @Override
      public CompletableFuture<TransferRecord> moneyDeposit(
          ExchangeMenuRequest.TransferDraft draft) {
        return money.deposit(draft.requestId(), draft.accountId(), draft.assetId(), draft.amount());
      }

      @Override
      public CompletableFuture<TransferRecord> moneyWithdrawal(
          ExchangeMenuRequest.TransferDraft draft) {
        return money.withdraw(draft.requestId(), draft.accountId(), draft.assetId(), draft.amount());
      }

      @Override
      public CompletableFuture<TransferRecord> itemDeposit(ExchangeMenuRequest.TransferDraft draft) {
        return items.deposit(draft.requestId(), draft.accountId(), draft.marketId(), draft.quantity());
      }

      @Override
      public CompletableFuture<TransferRecord> itemWithdrawal(
          ExchangeMenuRequest.TransferDraft draft) {
        return items.withdraw(draft.requestId(), draft.accountId(), draft.marketId(), draft.quantity());
      }
    });
  }

  ExchangeActionService(Map<String, PersistentOrderService> markets, TransferActions transfers) {
    this.markets = Map.copyOf(Objects.requireNonNull(markets, "markets"));
    this.transfers = Objects.requireNonNull(transfers, "transfers");
  }

  public OrderReceipt submitOrder(ExchangeMenuRequest.OrderDraft draft) throws SQLException {
    Objects.requireNonNull(draft, "draft");
    PersistentOrderService market = market(draft.marketId());
    return market.place(new OrderRequest(draft.requestId(), draft.accountId(), draft.marketId(),
        draft.side(), draft.type().name(), draft.price(), draft.slippageBoundary(), draft.quantity()));
  }

  /** Cancels a player's own order while preserving request idempotency. */
  public OrderReceipt cancel(UUID accountId, UUID requestId, UUID orderId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    List<SQLException> databaseFailures = new ArrayList<>();
    for (PersistentOrderService market : markets.values()) {
      try {
        return market.cancel(accountId, requestId, orderId);
      } catch (IllegalArgumentException failure) {
        // The order may belong to a later configured market.
      } catch (SQLException failure) {
        databaseFailures.add(failure);
      }
    }
    if (!databaseFailures.isEmpty()) {
      throw firstCancellationFailure(databaseFailures);
    }
    throw new IllegalArgumentException("order is not open: " + orderId);
  }

  static SQLException firstCancellationFailure(List<SQLException> failures) {
    if (failures == null || failures.isEmpty()) {
      throw new IllegalArgumentException("at least one cancellation failure is required");
    }
    SQLException first = failures.getFirst();
    for (int index = 1; index < failures.size(); index++) {
      SQLException later = failures.get(index);
      if (later != first) {
        first.addSuppressed(later);
      }
    }
    return first;
  }

  public CompletableFuture<TransferRecord> submitTransfer(
      ExchangeMenuRequest.TransferDraft draft) {
    Objects.requireNonNull(draft, "draft");
    return switch (draft.kind()) {
      case MONEY_DEPOSIT -> transfers.moneyDeposit(draft);
      case MONEY_WITHDRAWAL -> transfers.moneyWithdrawal(draft);
      case ITEM_DEPOSIT -> {
        market(draft.marketId());
        yield transfers.itemDeposit(draft);
      }
      case ITEM_WITHDRAWAL -> {
        market(draft.marketId());
        yield transfers.itemWithdrawal(draft);
      }
    };
  }

  public PersistentOrderService market(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    PersistentOrderService service = markets.get(marketId);
    if (service == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return service;
  }

  interface TransferActions {
    CompletableFuture<TransferRecord> moneyDeposit(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> moneyWithdrawal(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> itemDeposit(ExchangeMenuRequest.TransferDraft draft);

    CompletableFuture<TransferRecord> itemWithdrawal(ExchangeMenuRequest.TransferDraft draft);
  }
}
