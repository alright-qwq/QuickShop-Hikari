package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.ghostchu.quickshop.addon.exchange.command.CommandActor;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandbookAdminCommandsTest {
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
  void reportsSuccessOnActorCompletionAfterTargetOwnerMutation() throws Exception {
    PlayerMock target = server.addPlayer("Trader");
    Actor actor = new Actor();
    AtomicInteger schedules = new AtomicInteger();
    HandbookAdminCommands commands = new HandbookAdminCommands(
        name -> target,
        service(true)::give,
        (player, action, retired) -> {
          assertThat(player).isSameAs(target);
          schedules.incrementAndGet();
          action.run();
        });

    commands.give(actor, "Trader");

    assertThat(schedules).hasValue(1);
    assertThat(actor.completions).hasValue(1);
    assertThat(actor.message).isEqualTo("admin-handbook-given");
    assertThat(actor.arguments).containsExactly("Trader");
  }

  @Test
  void reportsNotFoundWithoutScheduling() throws Exception {
    Actor actor = new Actor();
    AtomicInteger schedules = new AtomicInteger();
    HandbookAdminCommands commands = new HandbookAdminCommands(
        ignored -> null,
        service(true)::give,
        (player, action, retired) -> schedules.incrementAndGet());

    commands.give(actor, "Missing");

    assertThat(schedules).hasValue(0);
    assertThat(actor.message).isEqualTo("admin-handbook-player-not-found");
    assertThat(actor.arguments).containsExactly("Missing");
  }

  @Test
  void unsuccessfulGiveAndSchedulingFailureBothReportTerminalFailure() throws Exception {
    PlayerMock target = server.addPlayer("Blocked");
    Actor rejectedActor = new Actor();
    HandbookAdminCommands rejected = new HandbookAdminCommands(
        ignored -> target,
        (player, allowDuplicate) -> ExchangeHandbookService.GiveResult.NO_SPACE,
        (player, action, retiredAction) -> action.run());

    rejected.give(rejectedActor, "Blocked");

    assertThat(rejectedActor.message).isEqualTo("admin-command-failed");
    assertThat(rejectedActor.completions).hasValue(1);

    Actor schedulingActor = new Actor();
    HandbookAdminCommands schedulingFailed = new HandbookAdminCommands(
        ignored -> target,
        service(true)::give,
        (player, action, retiredAction) -> {
          throw new IllegalStateException("scheduler unavailable");
        });

    schedulingFailed.give(schedulingActor, "Blocked");

    assertThat(schedulingActor.message).isEqualTo("admin-command-failed");
    assertThat(schedulingActor.completions).hasValue(1);
  }

  @Test
  void schedulerRetirementAndMutationFailureBothReportTerminalFailure() throws Exception {
    PlayerMock target = server.addPlayer("Leaving");
    Actor retiredActor = new Actor();
    HandbookAdminCommands retired = new HandbookAdminCommands(
        ignored -> target,
        service(true)::give,
        (player, action, retiredAction) -> retiredAction.run());

    retired.give(retiredActor, "Leaving");

    assertThat(retiredActor.message).isEqualTo("admin-command-failed");
    assertThat(retiredActor.completions).hasValue(1);

    Actor failedActor = new Actor();
    HandbookAdminCommands failed = new HandbookAdminCommands(
        ignored -> target,
        (player, allowDuplicate) -> {
          throw new IllegalStateException("inventory unavailable");
        },
        (player, action, retiredAction) -> action.run());

    failed.give(failedActor, "Leaving");

    assertThat(failedActor.message).isEqualTo("admin-command-failed");
    assertThat(failedActor.completions).hasValue(1);
  }

  private static ExchangeHandbookService service(boolean enabled) {
    return new ExchangeHandbookService(
        new NamespacedKey("exchange", "admin-handbook"),
        ExchangeHandbookSettings.create(enabled, true, true, "KNOWLEDGE_BOOK"),
        messages(), ignored -> Locale.US);
  }

  private static AddonMessageService messages() {
    return new AddonMessageService(Map.of("en-US", Map.of(
        "handbook-title", "Handbook",
        "handbook-lore-1", "Open Exchange",
        "handbook-lore-2", "Portable",
        "handbook-claim-success", "Received",
        "handbook-disabled", "Disabled")));
  }

  private static final class Actor implements CommandActor {
    private final AtomicInteger completions = new AtomicInteger();
    private String message;
    private Object[] arguments = new Object[0];

    @Override public UUID accountId() { return UUID.randomUUID(); }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void openMenu(String menuName, int page) { }
    @Override public void message(String key, Object... arguments) {
      message = key;
      this.arguments = arguments;
    }
    @Override public void dispatchCompletion(Runnable completion) {
      completions.incrementAndGet();
      completion.run();
    }
  }
}
