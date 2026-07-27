package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderBookRecoveryServiceTest {
  @Test
  void rebuildsSamePriceOrdersInOriginalFifoOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(1);
    UUID secondSeller = fixture.accountWithItems(1);
    fixture.service().place(limitSell(firstSeller, 1));
    fixture.service().place(limitSell(secondSeller, 1));

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", Instant.now());

    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::prioritySequence)
        .containsExactly(1L, 2L);
    assertThat(recovered.prioritySequence()).isEqualTo(2);
    assertThat(recovered.matchSequence()).isZero();
  }

  @Test
  void preservesPartialQuantityAndFifoPriority() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID firstSeller = fixture.accountWithItems(2);
    UUID secondSeller = fixture.accountWithItems(1);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(limitSell(firstSeller, 2));
    fixture.service().place(limitSell(secondSeller, 1));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 1));

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", Instant.now());

    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::prioritySequence)
        .containsExactly(1L, 2L);
    assertThat(recovered.book().orders(OrderSide.SELL))
        .extracting(Order::remainingQuantity)
        .containsExactly(1L, 1L);
  }

  @Test
  void restoresReferenceWindowAndDiscoveryQuantityExactly() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));
    Instant recoveredAt = Instant.now();

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", recoveredAt);
    recovered.referencePrices().record(
        new BigDecimal("105.00"), 1, recoveredAt.plusMillis(1));

    assertThat(recovered.referencePrices().referenceAt(recoveredAt.plusMillis(1)))
        .isEqualByComparingTo("102.55");
    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(51);
  }

  @Test
  void replaysV1HistoryOnceAndPersistsExactMetadata() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));
    long beforeVersion = fixture.marketVersion();
    fixture.clearMarketRiskMetadata();

    RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", Instant.now());

    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(50);
    assertThat(recovered.circuitBreaker().level()).isZero();
    assertThat(recovered.marketVersion()).isEqualTo(beforeVersion + 1);
    assertThat(fixture.marketDiscoveryQuantity()).isEqualTo("50");
    assertThat(fixture.marketCircuitBreakerLevel()).isEqualTo("0");
  }

  @Test
  void corruptSequenceLeavesMarketRecovering() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    fixture.service().place(limitSell(seller, 1));
    fixture.setMarketPrioritySequence(0);

    assertThatThrownBy(() -> fixture.recovery().recover("diamond-usd", Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sequence");
    assertThat(fixture.marketStatus()).isEqualTo("RECOVERING");
  }

  @Test
  void expiresOldSamplesButRetainsDiscoveryQuantity() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(50);
    UUID buyer = fixture.accountWithCurrency("10000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("105.00"), null, 50));
    fixture.service().place(new OrderRequest(UUID.randomUUID(), buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("105.00"), null, 50));

    RecoveredMarket recovered = fixture.recovery().recover(
        "diamond-usd", Instant.now().plus(Duration.ofMinutes(6)));

    assertThat(recovered.referencePrices().samples()).isEmpty();
    assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(50);
  }

  private static OrderRequest limitSell(UUID accountId, long quantity) {
    return new OrderRequest(UUID.randomUUID(), accountId, "diamond-usd", OrderSide.SELL,
        "LIMIT", new BigDecimal("100.00"), null, quantity);
  }
}
