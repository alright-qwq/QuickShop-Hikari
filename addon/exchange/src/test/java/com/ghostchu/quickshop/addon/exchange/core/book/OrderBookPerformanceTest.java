package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTimeout;

class OrderBookPerformanceTest {
  @Test
  @Tag("performance")
  void insertsAndCancelsOneHundredThousandOrdersWithinBaseline() {
    assertTimeout(Duration.ofSeconds(8), () -> {
      OrderBook book = new OrderBook();
      UUID[] ids = new UUID[100_000];
      for (int i = 0; i < ids.length; i++) {
        ids[i] = UUID.randomUUID();
        book.add(new Order(ids[i], UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
            i % 2 == 0 ? OrderSide.BUY : OrderSide.SELL,
            OrderType.LIMIT, TimeInForce.GTC,
            BigDecimal.valueOf(80 + (i % 41)).setScale(2), null,
            1, 1, OrderStatus.OPEN, i + 1L, 1, 1, Instant.EPOCH, Instant.EPOCH));
      }
      for (int i = 0; i < ids.length; i += 2) {
        book.cancel(ids[i]);
      }
    });
  }
}
