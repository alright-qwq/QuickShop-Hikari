package com.ghostchu.quickshop.addon.exchange.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure coverage for click-first order builder state. */
final class OrderBuilderPageSessionTest {
  private final UUID accountId = UUID.randomUUID();
  private final UUID requestId = UUID.randomUUID();

  @Test
  void defaultsUseExecutableReferenceAndProtectedMarketBoundary() {
    OrderBuilderPage.Session session = OrderBuilderPage.Session.initial(requestId, accountId, market(
        "101.00", "100.00"), OrderSide.BUY, OrderType.LIMIT);
    assertEquals(0, BigDecimal.valueOf(100).compareTo(session.price()));
    assertEquals(session.price().compareTo(BigDecimal.ZERO), 1);
    assertTrue(session.ready(), "default draft is valid");
  }

  @Test
  void quantityAndEstimateFollowSelections() {
    OrderBuilderPage.Session session = OrderBuilderPage.Session.initial(requestId, accountId,
        market("101.00", "100.00"), OrderSide.BUY, OrderType.LIMIT);
    assertTrue(session.ready(), "initial draft is valid");
    session.quantity(4);
    assertTrue(session.ready());
    assertEquals(0, session.estimate().compareTo(BigDecimal.valueOf(400)));
  }

  private MarketRow market(String bid, String ask) {
    return new MarketRow("diamond-usd", "Diamond USD", new BigDecimal("100"),
        new BigDecimal(bid), new BigDecimal(ask), BigDecimal.ZERO, 1, MarketStatus.OPEN);
  }
}
