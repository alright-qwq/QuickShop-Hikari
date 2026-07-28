package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminCommandRouterTest {
  @Test
  void cancelsAnOpenOrderWithTheDedicatedOrdersPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(1);
    OrderReceipt order = fixture.service().place(new OrderRequest(UUID.randomUUID(), seller,
        fixture.rules().marketId(), OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 1));
    Actor actor = new Actor("quickshop.exchange.admin.orders");
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);

    router.execute(actor, new String[] {"order", "cancel", order.orderId().toString(),
        "suspected abuse"});

    assertThat(fixture.orderStatus(order.orderId())).isEqualTo("CANCELLED");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  @Test
  void deniesOrderCancellationWithoutTheDedicatedPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service())), UUID::randomUUID);
    Actor actor = new Actor();

    router.execute(actor, new String[] {"order", "cancel", UUID.randomUUID().toString(),
        "suspected abuse"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void pausesAMarketWithTheDedicatedMarketPermission() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    Actor actor = new Actor("quickshop.exchange.admin.market");
    AdminCommandRouter router = new AdminCommandRouter(new AdminExchangeService(
        fixture.repository(), Map.of(fixture.rules().marketId(), fixture.service())),
        UUID::randomUUID);

    router.execute(actor, new String[] {"market", "pause", fixture.rules().marketId(),
        "scheduled maintenance"});

    assertThat(fixture.marketStatus()).isEqualTo("PAUSED");
    assertThat(actor.message).isEqualTo("request-accepted");
  }

  private static final class Actor implements CommandActor {
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private String message;

    private Actor(String... permissions) {
      this.permissions.addAll(Set.of(permissions));
    }

    @Override public UUID accountId() { return accountId; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void message(String key, Object... arguments) { message = key; }
    @Override public void openMenu(String menuName, int page) { }
  }
}
