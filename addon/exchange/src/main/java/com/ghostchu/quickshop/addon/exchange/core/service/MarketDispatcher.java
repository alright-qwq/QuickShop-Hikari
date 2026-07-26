package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MarketDispatcher implements AutoCloseable {
  private final RequestResultStore requestResults;
  private final MarketCommandProcessor processor;
  private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

  public MarketDispatcher(RequestResultStore requestResults, MarketCommandProcessor processor) {
    this.requestResults = requestResults;
    this.processor = processor;
  }

  public CompletableFuture<CommandResult> submit(ExchangeCommand command) {
    ExecutorService executor = executors.computeIfAbsent(command.marketId(), market ->
        Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("qs-exchange-" + market + "-", 0).factory()));
    return CompletableFuture.supplyAsync(() ->
        requestResults.find(command.accountId(), command.requestId()).orElseGet(() -> {
          CommandResult result = processor.process(command);
          return requestResults.putIfAbsent(command.accountId(), command.requestId(), result);
        }), executor);
  }

  @Override
  public void close() {
    executors.values().forEach(ExecutorService::shutdown);
    executors.values().forEach(executor -> {
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException interrupted) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    });
  }
}
