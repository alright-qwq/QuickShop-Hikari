package com.ghostchu.quickshop.addon.exchange.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainValidationTest {
  @Test
  void rejectsPriceOffTick() {
    assertThatThrownBy(() -> rules().validatePrice(new BigDecimal("10.02")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("price is not aligned to tickSize");
  }

  @Test
  void marketOrderMustBeIoc() {
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, null,
        new BigDecimal("12.00"), 5, 5, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH))
        .hasMessage("market order requires IOC");
  }

  @Test
  void generatesMonotonicVersionSevenIdsWithinOneMillisecond() {
    TimeOrderedIdGenerator ids =
        new TimeOrderedIdGenerator(() -> 1_721_952_000_000L, new java.util.Random(7));
    UUID first = ids.get();
    UUID second = ids.get();
    assertThat(first.version()).isEqualTo(7);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(second.compareTo(first)).isPositive();
  }

  private static MarketRules rules() {
    return new MarketRules("diamond-usd", "USD", new BigDecimal("100.00"),
        new BigDecimal("1.00"), new BigDecimal("10000.00"), new BigDecimal("0.05"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }
}
