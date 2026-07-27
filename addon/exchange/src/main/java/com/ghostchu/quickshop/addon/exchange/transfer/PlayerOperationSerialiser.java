package com.ghostchu.quickshop.addon.exchange.transfer;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class PlayerOperationSerialiser implements AutoCloseable {
  private final Map<UUID, ExecutorService> executors = new ConcurrentHashMap<>();

  public <T> CompletableFuture<T> submit(UUID playerId, Supplier<T> operation) {
    Objects.requireNonNull(playerId, "playerId");
    Objects.requireNonNull(operation, "operation");
    ExecutorService executor = executors.computeIfAbsent(playerId, id ->
        Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("qs-exchange-account-" + id + "-", 0).factory()));
    return CompletableFuture.supplyAsync(operation, executor);
  }

  @Override
  public void close() {
    executors.values().forEach(ExecutorService::shutdown);
    executors.clear();
  }
}
