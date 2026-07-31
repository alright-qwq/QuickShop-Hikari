package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeMenuLifecycleTest {
  @Test
  void observesInventoryCloseAfterTnmlHasUpdatedTheViewer() throws NoSuchMethodException {
    EventHandler handler = ExchangeMenuListener.class
        .getMethod("onInventoryClose", org.bukkit.event.inventory.InventoryCloseEvent.class)
        .getAnnotation(EventHandler.class);

    assertThat(handler.priority()).isEqualTo(EventPriority.MONITOR);
  }

  @Test
  void preservesContextWhenTheExchangeInventoryClosesDuringPageSwitch() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    ExchangeMenuLifecycle lifecycle = new ExchangeMenuLifecycle(contexts);
    UUID player = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.page("assets");
    contexts.put(player, request);

    lifecycle.inventoryClosed(player, ExchangeMenu.TITLE, true);

    assertThat(contexts.get(player)).contains(request);
  }

  @Test
  void clearsContextWhenTheRemainingViewerBelongsToAnotherMenu() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    ExchangeMenuLifecycle lifecycle = new ExchangeMenuLifecycle(contexts);
    UUID player = UUID.randomUUID();
    contexts.put(player, ExchangeMenuRequest.page("assets"));

    lifecycle.inventoryClosed(player, ExchangeMenu.TITLE, true, "qs:history");

    assertThat(contexts.get(player)).isEmpty();
  }

  @Test
  void clearsOnlyTheContextForAnExchangeInventoryCloseOrPlayerQuit() {
    ExchangeMenuContextStore contexts = new ExchangeMenuContextStore();
    AtomicInteger cleanups = new AtomicInteger();
    ExchangeMenuLifecycle lifecycle = new ExchangeMenuLifecycle(
        contexts, ignored -> cleanups.incrementAndGet());
    UUID player = UUID.randomUUID();
    contexts.put(player, ExchangeMenuRequest.page("markets"));

    lifecycle.inventoryClosed(player, "Chest");
    assertThat(contexts.get(player)).isPresent();
    assertThat(cleanups).hasValue(0);

    lifecycle.inventoryClosed(player, ExchangeMenu.TITLE);
    assertThat(contexts.get(player)).isEmpty();
    assertThat(cleanups).hasValue(0);

    contexts.put(player, ExchangeMenuRequest.page("markets"));
    lifecycle.playerQuit(player);
    assertThat(contexts.get(player)).isEmpty();
    assertThat(cleanups).hasValue(1);
  }
}
