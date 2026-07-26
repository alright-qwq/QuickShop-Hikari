package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LimitMatchingTest {
  @Test
  void fillsAcrossMakersAtTheirPricesAndRestsRemainder() {
    AtomicLong matches = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(new OrderBook(), matches::incrementAndGet,
        () -> Instant.parse("2026-07-26T00:00:00Z"), UUID::randomUUID);
    engine.submit(order(OrderSide.SELL, "99.00", 4, 1));
    engine.submit(order(OrderSide.SELL, "100.00", 4, 2));

    MatchResult result = engine.submit(order(OrderSide.BUY, "101.00", 10, 3));

    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("99.00"), new BigDecimal("100.00"));
    assertThat(result.trades()).extracting(Trade::quantity).containsExactly(4L, 4L);
    assertThat(result.finalOrder().remainingQuantity()).isEqualTo(2);
    assertThat(result.rested()).isTrue();
  }

  private static Order order(OrderSide side, String price, long quantity, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
