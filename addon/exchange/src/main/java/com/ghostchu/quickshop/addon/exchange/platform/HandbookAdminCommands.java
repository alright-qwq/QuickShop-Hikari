package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.command.AdminCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.CommandActor;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.entity.Player;

/** Safely performs administrator handbook delivery at the target player's owner boundary. */
public final class HandbookAdminCommands implements AdminCommandRouter.HandbookCommands {
  private final Function<String, Player> playerLookup;
  private final HandbookGiver giver;
  private final EntityScheduler scheduler;

  public HandbookAdminCommands(
      Function<String, Player> playerLookup,
      HandbookGiver giver,
      EntityScheduler scheduler) {
    this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    this.giver = Objects.requireNonNull(giver, "giver");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
  }

  @Override
  public void give(CommandActor actor, String playerName) {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(playerName, "playerName");
    Player target = playerLookup.apply(playerName);
    if (target == null) {
      actor.message("admin-handbook-player-not-found", playerName);
      return;
    }
    String targetName = target.getName();
    try {
      scheduler.schedule(
          target,
          () -> giveAtTargetOwner(actor, target, targetName),
          () -> report(actor, "admin-command-failed"));
    } catch (RuntimeException failure) {
      report(actor, "admin-command-failed");
    }
  }

  private void giveAtTargetOwner(CommandActor actor, Player target, String targetName) {
    try {
      ExchangeHandbookService.GiveResult result = giver.give(target, true);
      if (result == ExchangeHandbookService.GiveResult.SUCCESS) {
        report(actor, "admin-handbook-given", targetName);
      } else {
        report(actor, "admin-command-failed");
      }
    } catch (RuntimeException failure) {
      report(actor, "admin-command-failed");
    }
  }

  private static void report(CommandActor actor, String key, Object... arguments) {
    try {
      actor.dispatchCompletion(() -> actor.message(key, arguments));
    } catch (RuntimeException ignored) {
      // The administrator may have disconnected while the target-owner action was running.
    }
  }

  @FunctionalInterface
  public interface HandbookGiver {
    ExchangeHandbookService.GiveResult give(Player player, boolean allowDuplicate);
  }

  @FunctionalInterface
  public interface EntityScheduler {
    void schedule(Player player, Runnable action, Runnable retired);
  }
}
