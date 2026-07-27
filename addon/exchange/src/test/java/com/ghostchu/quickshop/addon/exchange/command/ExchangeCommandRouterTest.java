package com.ghostchu.quickshop.addon.exchange.command;

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

  private static final class Actor implements CommandActor {
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private Actor(String permission) { permissions.add(permission); }
    public UUID accountId() { return UUID.randomUUID(); }
    public boolean hasPermission(String permission) { return permissions.contains(permission); }
    public void message(String key, Object... arguments) {
      message = key + (arguments.length == 0 ? "" : ":" + arguments[0]);
    }
    public void openMenu(String menuName, int page) { }
  }
}
