package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Starts recovery asynchronously after the player has joined. */
public final class TransferLoginListener implements Listener {
  private final Function<UUID, CompletableFuture<?>> recovery;
  private final BiConsumer<UUID, Throwable> failureReporter;

  public TransferLoginListener(TransferRecoveryService recovery) {
    this(recovery::recoverPlayer);
  }

  public TransferLoginListener(Function<UUID, CompletableFuture<?>> recovery) {
    this(recovery, (accountId, failure) -> {});
  }

  public TransferLoginListener(
      Function<UUID, CompletableFuture<?>> recovery,
      BiConsumer<UUID, Throwable> failureReporter) {
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    recover(event.getPlayer().getUniqueId());
  }

  void recover(UUID accountId) {
    Objects.requireNonNull(accountId, "accountId");
    try {
      CompletableFuture<?> submitted = Objects.requireNonNull(
          recovery.apply(accountId), "recovery future");
      submitted.whenComplete((ignored, failure) -> {
        if (failure != null) {
          failureReporter.accept(accountId, unwrap(failure));
        }
      });
    } catch (RuntimeException failure) {
      failureReporter.accept(accountId, failure);
    }
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof java.util.concurrent.CompletionException completion
        && completion.getCause() != null) {
      return completion.getCause();
    }
    return failure;
  }
}
