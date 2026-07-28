package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCommandRouterTest {
  @Test
  void deniesMarketOrderWithoutDedicatedPermission() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "market", "buy", "diamond-usd", "5"});
    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void generatesOneRequestIdPerConfirmedAction() {
    Actor actor = new Actor("quickshop.exchange.order.limit");
    UUID request = UUID.randomUUID();
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "buy", "diamond-usd", "100.00", "5"});
    assertThat(actor.message).isEqualTo("request-accepted:" + request);
  }

  @Test
  void preservesMarketIdInsteadOfOpeningAnArbitraryMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"market", "diamond-usd"});
    assertThat(actor.opened).isNotNull();
    assertThat(actor.opened.marketId()).isEqualTo("diamond-usd");
    assertThat(actor.opened.menuName()).isEqualTo("market-detail");
  }

  @Test
  void rejectsUnknownCommandWithoutOpeningAUserControlledMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"not-a-menu"});
    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void parsesLimitOrderIntoAContextWithTheGeneratedRequestId() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.order.limit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "sell", "diamond-usd", "100.00", "5"});
    assertThat(actor.opened.order().requestId()).isEqualTo(request);
    assertThat(actor.opened.order().side()).isEqualTo(OrderSide.SELL);
    assertThat(actor.opened.order().type()).isEqualTo(OrderType.LIMIT);
  }

  @Test
  void parsesMoneyDepositIntoAContext() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.deposit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"deposit", "money", "default", "12.50"});
    assertThat(actor.opened.transfer().requestId()).isEqualTo(request);
    assertThat(actor.opened.transfer().kind())
        .isEqualTo(ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT);
    assertThat(actor.opened.transfer().amount()).isEqualByComparingTo("12.50");
  }

  private static final class Actor implements CommandActor {
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private ExchangeMenuRequest opened;
    private Actor(String permission) { permissions.add(permission); }
    public UUID accountId() { return UUID.randomUUID(); }
    public boolean hasPermission(String permission) { return permissions.contains(permission); }
    public void message(String key, Object... arguments) {
      message = key + (arguments.length == 0 ? "" : ":" + arguments[0]);
    }
    public void openMenu(String menuName, int page) { }
    public void openMenu(ExchangeMenuRequest request) { opened = request; }
  }
}
