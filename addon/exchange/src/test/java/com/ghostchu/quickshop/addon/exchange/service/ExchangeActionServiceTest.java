package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeActionServiceTest {
  @Test
  void rejectsAnOrderForAnUnknownMarketBeforeCallingAService() {
    ExchangeMenuRequest.OrderDraft draft = new ExchangeMenuRequest.OrderDraft(
        UUID.randomUUID(), UUID.randomUUID(), "missing", OrderSide.BUY, OrderType.LIMIT,
        new BigDecimal("1.00"), null, 1);
    ExchangeActionService actions = new ExchangeActionService(Map.of(), transfers());

    assertThatThrownBy(() -> actions.submitOrder(draft))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown market");
  }

  @Test
  void preservesTheFirstDatabaseFailureAcrossMarketCancellationLookup() {
    SQLException first = new SQLException("first database failure");
    SQLException second = new SQLException("second database failure");

    SQLException combined = ExchangeActionService.firstCancellationFailure(
        List.of(first, second));

    org.assertj.core.api.Assertions.assertThat((Object) combined).isSameAs(first);
    org.assertj.core.api.Assertions.assertThat(combined.getSuppressed()).containsExactly(second);
  }

  private static ExchangeActionService.TransferActions transfers() {
    return new ExchangeActionService.TransferActions() {
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          moneyDeposit(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          moneyWithdrawal(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          itemDeposit(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
      public java.util.concurrent.CompletableFuture<com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>
          itemWithdrawal(ExchangeMenuRequest.TransferDraft draft) { throw new AssertionError(); }
    };
  }
}
