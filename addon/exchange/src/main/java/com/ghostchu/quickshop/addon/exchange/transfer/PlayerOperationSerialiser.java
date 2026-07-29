package com.ghostchu.quickshop.addon.exchange.transfer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serialises operations per account on a shared bounded worker pool. */
public final class PlayerOperationSerialiser implements AutoCloseable {
  private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(30);
  private static final int DEFAULT_WORKERS = Math.max(2,
      Math.min(4, Runtime.getRuntime().availableProcessors()));
  private static final int DEFAULT_CAPACITY = 1_024;

  private final Map<UUID, CompletableFuture<Void>> accountTails = new HashMap<>();
  private final ExecutorService executor;
  private final Semaphore capacity;
  private final Duration closeTimeout;
  private boolean accepting = true;
  private boolean terminated;

  public PlayerOperationSerialiser() {
    this(DEFAULT_CLOSE_TIMEOUT, DEFAULT_WORKERS, DEFAULT_CAPACITY);
  }

  public PlayerOperationSerialiser(Duration closeTimeout) {
    this(closeTimeout, DEFAULT_WORKERS, DEFAULT_CAPACITY);
  }

  PlayerOperationSerialiser(Duration closeTimeout, int workers, int maxAcceptedOperations) {
    this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
    if (closeTimeout.isZero() || closeTimeout.isNegative()) {
      throw new IllegalArgumentException("closeTimeout must be positive");
    }
    if (workers <= 0) {
      throw new IllegalArgumentException("workers must be positive");
    }
    if (maxAcceptedOperations <= 0) {
      throw new IllegalArgumentException("maxAcceptedOperations must be positive");
    }
    capacity = new Semaphore(maxAcceptedOperations);
    executor = new ThreadPoolExecutor(
        workers,
        workers,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(maxAcceptedOperations),
        Thread.ofPlatform().daemon(true).name("qs-exchange-account-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }

  public synchronized <T> CompletableFuture<T> submit(UUID playerId, Supplier<T> operation) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operation, "operation");
    if (!accepting) {
      throw new IllegalStateException("player operation serialiser is closed");
    }
    if (!capacity.tryAcquire()) {
      throw new RejectedExecutionException("player operation capacity is full");
    }

    CompletableFuture<T> result = new CompletableFuture<>();
    CompletableFuture<Void> predecessor = accountTails.getOrDefault(
        playerId, CompletableFuture.completedFuture(null));
    CompletableFuture<Void> tail;
    try {
      tail = predecessor.handle((ignored, failure) -> null)
          .thenRunAsync(() -> runOperation(operation, result), executor);
    } catch (RuntimeException rejection) {
      capacity.release();
      throw rejection;
    }
    accountTails.put(playerId, tail);
    tail.whenComplete((ignored, failure) -> operationFinished(playerId, tail));
    return result;
  }

  private static <T> void runOperation(Supplier<T> operation, CompletableFuture<T> result) {
    try {
      result.complete(operation.get());
    } catch (Throwable failure) {
      result.completeExceptionally(failure);
    }
  }

  private synchronized void operationFinished(UUID playerId, CompletableFuture<Void> tail) {
    accountTails.remove(playerId, tail);
    capacity.release();
  }

  synchronized int activeAccountCount() {
    return accountTails.size();
  }

  @Override
  public void close() {
    CompletableFuture<?> accepted;
    synchronized (this) {
      if (terminated) {
        return;
      }
      accepting = false;
      accepted = CompletableFuture.allOf(
          accountTails.values().toArray(CompletableFuture[]::new));
    }

    long deadline = System.nanoTime() + closeTimeout.toNanos();
    try {
      accepted.get(closeTimeout.toNanos(), TimeUnit.NANOSECONDS);
      executor.shutdown();
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0L || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
        throw new IllegalStateException("timed out draining player operations during close");
      }
      synchronized (this) {
        terminated = true;
      }
    } catch (java.util.concurrent.TimeoutException failure) {
      throw new IllegalStateException("timed out draining player operations during close", failure);
    } catch (java.util.concurrent.ExecutionException failure) {
      throw new IllegalStateException("failed while draining player operations", failure.getCause());
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while draining player operations", failure);
    }
  }
}
