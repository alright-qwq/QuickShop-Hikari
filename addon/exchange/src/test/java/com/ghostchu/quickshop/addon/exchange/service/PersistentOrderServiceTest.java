package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentOrderServiceTest {
  @Test
  void locksOnlyAssetsInvolvedInTheOrderOrItsTrades() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(2);

    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(fixture.hasCurrencyBalance(seller)).isFalse();
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("90.00"), null, 1));

    assertThat(fixture.hasInventoryBalance(buyer)).isFalse();
    assertThat(fixture.hasCurrencyBalance(seller)).isFalse();
  }

  @Test
  void rejectsEveryNonOpenMarketWithoutReservationOrStateMutation() throws Exception {
    for (String status : Set.of("HALTED", "PAUSED", "RECOVERING", "CLOSED")) {
      ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
      UUID seller = fixture.accountWithItems(2);
      fixture.setMarketStatus(status);

      assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
          UUID.randomUUID(), seller, "diamond-usd", OrderSide.SELL, "LIMIT",
          new BigDecimal("100.00"), null, 1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(status);

      assertThat(fixture.orderCount()).isZero();
      assertThat(fixture.availableItems(seller)).isEqualTo(2);
      assertThat(fixture.frozenItems(seller)).isZero();
      assertThat(fixture.marketPrioritySequence()).isZero();
      assertThat(fixture.marketStatus()).isEqualTo(status);
    }
  }

  @Test
  void commitsTradeAndReturnsSameReceiptForDuplicateRequest() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(10);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    UUID request = UUID.randomUUID();
    OrderRequest buy = new OrderRequest(request, buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 2);

    OrderReceipt first = fixture.service().place(buy);
    OrderReceipt duplicate = fixture.service().place(buy);

    assertThat(duplicate).isEqualTo(first);
    assertThat(first.trades()).hasSize(1);
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.ledgerIsBalanced()).isTrue();
    assertThat(fixture.feeAccountBalance()).isPositive();
  }

  @Test
  void rollsBackSettlementMarksRecoveringAndInvokesRecoveryAfterSqlFailure() throws Exception {
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    AtomicReference<Throwable> recoveryFailure = new AtomicReference<>();
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite((market, failure) -> {
      recoveredMarket.set(market);
      recoveryFailure.set(failure);
    });
    UUID seller = fixture.accountWithItems(10);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    fixture.failTradeInserts();

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2))).isInstanceOf(SQLException.class);

    assertThat(fixture.tradeCount()).isZero();
    assertThat(fixture.orderCount()).isEqualTo(1);
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("1000.00");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("0");
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
    assertThat(recoveredMarket).hasValue("diamond-usd");
    assertThat(recoveryFailure.get()).isInstanceOf(SQLException.class);
  }

  @Test
  void releasesFilledLimitBuyPriceImprovementAndWritesRequiredJournalAccounts()
      throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));

    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("899.80");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("0");
    assertThat(fixture.journalAccountKinds()).containsExactlyInAnyOrderElementsOf(Set.of(
        "buyer-currency", "seller-currency", "fee-currency", "currency-custody",
        "seller-item", "buyer-item", "item-custody"));
  }

  @Test
  void partiallyFilledLimitBuyRetainsOnlyRemainingWorstCaseReservation() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 2));

    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("789.58");
    assertThat(fixture.frozenCurrency(buyer)).isEqualByComparingTo("110.22");
  }

  @Test
  void persistsCircuitBreakerStateAndPreHaltReferenceWithTrade() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));

    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("HALTED");
    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("100.00");
    assertThat(fixture.marketLastPrice()).isEqualByComparingTo("120.00");
    assertThat(fixture.marketHaltedUntil()).isNotNull();
  }

  @Test
  void settlesValidZeroFeeMarketWithoutZeroAmountBalanceMutations() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqliteWithFees("0", "0");
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));

    OrderReceipt receipt = fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer,
        "diamond-usd", OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    assertThat(receipt.trades()).hasSize(1);
    assertThat(fixture.feeAccountBalance()).isEqualByComparingTo("0");
    assertThat(fixture.ledgerIsBalanced()).isTrue();
  }

  @Test
  void preservesSqlFailureWhenRecoveryCallbackAlsoFails() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite((market, failure) -> {
      throw new IllegalStateException("forced recovery failure");
    });
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    fixture.failTradeInserts();

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, "diamond-usd", OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(SQLException.class)
        .satisfies(failure -> assertThat(failure.getSuppressed())
            .anyMatch(suppressed -> suppressed instanceof IllegalStateException
                && suppressed.getMessage().equals("forced recovery failure")));
  }

  @Test
  void idempotencyConflictDoesNotPutMarketIntoRecovery() throws Exception {
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite(
        (market, failure) -> recoveredMarket.set(market));
    UUID seller = fixture.accountWithItems(1);
    UUID requestId = UUID.randomUUID();
    fixture.storeRequestResult(seller, requestId, "CANCEL", "{}");

    assertThatThrownBy(() -> fixture.service().place(new OrderRequest(
        requestId, seller, "diamond-usd", OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another operation");

    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(recoveredMarket).hasNullValue();
    assertThat(fixture.availableItems(seller)).isEqualTo(1);
    assertThat(fixture.frozenItems(seller)).isZero();
  }

  @Test
  void restartedServiceContinuesFromPersistedReferencePrice() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID firstBuyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 1));

    PersistentOrderService restarted = fixture.restartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("110.00"), null, 1));
    restarted.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));

    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(fixture.marketReferencePrice()).isEqualByComparingTo("100.15");
  }

  @Test
  void restartedServiceEscalatesLevelTwoAndWritesHighAlert() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID firstBuyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("110.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), firstBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("110.00"), null, 1));
    fixture.resumeMarket();

    PersistentOrderService restarted = fixture.restartedService();
    UUID secondSeller = fixture.accountWithItems(1);
    UUID secondBuyer = fixture.accountWithCurrency("1000.00");
    restarted.place(new OrderRequest(UUID.randomUUID(), secondSeller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));
    restarted.place(new OrderRequest(UUID.randomUUID(), secondBuyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.marketStatus()).isEqualTo("HALTED");
    assertThat(fixture.highAlertCount()).isEqualTo(1);
  }

  @Test
  void concurrentDuplicateAcrossServiceInstancesReturnsCommittedReceipt() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    OrderRequest request = new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1);
    PersistentOrderService secondService = fixture.restartedService();
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<OrderReceipt> first = executor.submit(() -> {
        start.await();
        return fixture.service().place(request);
      });
      Future<OrderReceipt> second = executor.submit(() -> {
        start.await();
        return secondService.place(request);
      });
      start.countDown();

      assertThat(second.get()).isEqualTo(first.get());
    }
    assertThat(fixture.orderCount()).isEqualTo(2);
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
  }

  @Test
  void reportedCommitFailureReturnsDurableReceiptWithoutRecovery() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    AtomicReference<String> recoveredMarket = new AtomicReference<>();
    PersistentOrderService uncertain = fixture.serviceWithReportedCommitFailure(
        (market, failure) -> recoveredMarket.set(market));
    OrderRequest request = new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1);

    OrderReceipt receipt = uncertain.place(request);

    assertThat(receipt).isEqualTo(uncertain.place(request));
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(recoveredMarket).hasNullValue();
  }

  @Test
  void recoveryCanPublishRebuiltRuntimeRiskState() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    RiskLimits limits = RiskLimits.defaults();
    ReferencePriceTracker rebuiltPrices = ReferencePriceTracker.restored(
        new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);
    CircuitBreaker rebuiltBreaker = CircuitBreaker.restored(
        limits, MarketStatus.OPEN, new BigDecimal("100.00"),
        new BigDecimal("110.00"), null);
    fixture.service().publishRecoveredState(
        new OrderBook(), rebuiltPrices, rebuiltBreaker, fixture.marketVersion());
    UUID seller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");

    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("120.00"), null, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("120.00"), null, 1));

    assertThat(fixture.highAlertCount()).isEqualTo(1);
  }
}
