package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BukkitCommandActorTest {
  private static ServerMock server;

  @BeforeAll
  static void startServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopServer() {
    MockBukkit.unmock();
  }

  @Test
  void dispatchesAsynchronousCompletionThroughThePlayerEntityScheduler() {
    PlayerMock player = server.addPlayer();
    AtomicInteger scheduled = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();
    AddonMessageService messages = new AddonMessageService(Map.of());
    BukkitCommandActor actor = new BukkitCommandActor(player, messages, Locale.US,
        (menu, page) -> {}, (scheduledPlayer, completion) -> {
          assertThat(scheduledPlayer).isSameAs(player);
          scheduled.incrementAndGet();
          completion.run();
        });

    actor.dispatchCompletion(completed::incrementAndGet);

    assertThat(scheduled).hasValue(1);
    assertThat(completed).hasValue(1);
  }

  @Test
  void claimsHandbookThroughThePlayerEntityScheduler() {
    PlayerMock player = server.addPlayer();
    AtomicInteger scheduled = new AtomicInteger();
    AtomicInteger claimed = new AtomicInteger();
    AddonMessageService messages = new AddonMessageService(Map.of());
    BukkitCommandActor actor = new BukkitCommandActor(
        player,
        messages,
        Locale.US,
        (menu, page) -> {},
        (scheduledPlayer, completion) -> {
          assertThat(scheduledPlayer).isSameAs(player);
          scheduled.incrementAndGet();
          completion.run();
        },
        claimed::incrementAndGet);

    actor.claimHandbook();

    assertThat(scheduled).hasValue(1);
    assertThat(claimed).hasValue(1);
  }

  @Test
  void forwardsMessagesAndMenuOpeningToPlayerPorts() {
    PlayerMock player = server.addPlayer();
    AtomicReference<String> opened = new AtomicReference<>();
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of("permission-denied", "Denied")));
    BukkitCommandActor actor = new BukkitCommandActor(player, messages, Locale.US,
        (menu, page) -> opened.set(menu + ":" + page));

    assertThat(actor.accountId()).isEqualTo(player.getUniqueId());
    actor.message("permission-denied");
    actor.openMenu("markets", 2);

    assertThat(player.nextMessage()).isEqualTo("Denied");
    assertThat(opened).hasValue("markets:2");
  }
}
