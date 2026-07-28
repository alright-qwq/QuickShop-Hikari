package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMenuContextStoreTest {
  @Test
  void retainsTheSameRequestUntilPlayerClosesTheMenu() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.cancel(UUID.randomUUID(), UUID.randomUUID());

    store.put(player, request);

    assertThat(store.get(player)).containsSame(request);
    assertThat(store.remove(player)).containsSame(request);
    assertThat(store.get(player)).isEmpty();
  }

  @Test
  void closeDropsAllPendingContexts() {
    ExchangeMenuContextStore store = new ExchangeMenuContextStore();
    UUID player = UUID.randomUUID();
    store.put(player, ExchangeMenuRequest.page("markets"));

    store.close();

    assertThat(store.get(player)).isEmpty();
  }
}
