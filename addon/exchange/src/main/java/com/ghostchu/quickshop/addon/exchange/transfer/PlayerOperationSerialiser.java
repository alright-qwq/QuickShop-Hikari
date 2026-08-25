package com.ghostchu.quickshop.addon.exchange.transfer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class PlayerOperationSerialiser implements AutoCloseable {
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(30);

  private final Map<UUID, ExecutorService> executors = new HashMap<>();
  private final Duration closeTimeout;
  private boolean closed;

  public PlayerOperationSerialiser() {
    this(DEFAULT_CLOSE_TIMEOUT);
  }

  public PlayerOperationSerialiser(Duration closeTimeout) {
    this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isZero() || closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must be positive");
    }
  }

  public synchronized <T> CompletableFuture<T> submit(UUID playerId, Supplier<T> operation) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operation, "operation");
    if (closed) {
      throw new IllegalStateException("player operation serialiser is closed");
    }
    ExecutorService executor = executors.computeIfAbsent(playerId, id ->
        Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("qs-exchange-account-" + id + "-", 0).factory()));
    return CompletableFuture.supplyAsync(operation, executor);
  }

  @Override
  public void close() {
    List<ExecutorService> closing;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      closing = List.copyOf(executors.values());
      closing.forEach(ExecutorService::shutdown);
    }

    long deadline = System.nanoTime() + closeTimeout.toNanos();
    boolean interrupted = false;
    try {
      for (ExecutorService executor : closing) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
          closing.forEach(ExecutorService::shutdownNow);
          throw new IllegalStateException("timed out draining player operations during close");
        }
      }
    } catch (InterruptedException failure) {
      interrupted = true;
      closing.forEach(ExecutorService::shutdownNow);
      throw new IllegalStateException("interrupted while draining player operations", failure);
    } finally {
      synchronized (this) {
        executors.clear();
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
