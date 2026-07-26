package com.ghostchu.quickshop.addon.exchange.core.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDispatcherTest {
  @Test
  void returnsTheStoredResultForADuplicateAccountRequestPair() {
    AtomicInteger calls = new AtomicInteger();
    RequestResultStore store = resultStore();
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    ExchangeCommand command = command("diamond-usd", accountId, requestId);

    try (MarketDispatcher dispatcher = new MarketDispatcher(store,
        submitted -> new CommandResult(submitted.requestId(), "accepted-" + calls.incrementAndGet()))) {
      CommandResult first = dispatcher.submit(command).join();
      CommandResult duplicate = dispatcher.submit(command).join();
      CommandResult otherAccount = dispatcher.submit(command("diamond-usd", UUID.randomUUID(), requestId)).join();

      assertThat(first).isEqualTo(duplicate);
      assertThat(otherAccount).isNotEqualTo(first);
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void serializesCommandsForOneMarketOnOneThread() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch permitFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    Set<String> processorThreads = ConcurrentHashMap.newKeySet();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      processorThreads.add(Thread.currentThread().getName());
      int nowActive = active.incrementAndGet();
      maximumActive.accumulateAndGet(nowActive, Math::max);
      if (submitted.operation().equals("first")) {
        firstStarted.countDown();
        await(permitFirst);
      }
      active.decrementAndGet();
      return new CommandResult(submitted.requestId(), submitted.operation());
    });

    try {
      CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
      assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<CommandResult> second = dispatcher.submit(command("diamond-usd", "second"));

      assertThat(second).isNotDone();
      permitFirst.countDown();

      assertThat(first.join().outcome()).isEqualTo("first");
      assertThat(second.join().outcome()).isEqualTo("second");
      assertThat(maximumActive).hasValue(1);
      assertThat(processorThreads).hasSize(1);
    } finally {
      permitFirst.countDown();
      dispatcher.close();
    }
  }

  @Test
  void closeDrainsAcceptedMarketCommandsBeforeReturning() throws InterruptedException {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch permitFirst = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    MarketDispatcher dispatcher = new MarketDispatcher(resultStore(), submitted -> {
      calls.incrementAndGet();
      if (submitted.operation().equals("first")) {
        firstStarted.countDown();
        await(permitFirst);
      }
      return new CommandResult(submitted.requestId(), submitted.operation());
    });

    CompletableFuture<CommandResult> first = dispatcher.submit(command("diamond-usd", "first"));
    assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<CommandResult> second = dispatcher.submit(command("diamond-usd", "second"));
    CompletableFuture<Void> closing = CompletableFuture.runAsync(dispatcher::close);

    permitFirst.countDown();

    assertThat(closing).succeedsWithin(Duration.ofSeconds(2));
    assertThat(first.join().outcome()).isEqualTo("first");
    assertThat(second.join().outcome()).isEqualTo("second");
    assertThat(calls).hasValue(2);
  }

  private static ExchangeCommand command(String marketId, String operation) {
    return command(marketId, UUID.randomUUID(), UUID.randomUUID(), operation);
  }

  private static ExchangeCommand command(String marketId, UUID accountId, UUID requestId) {
    return command(marketId, accountId, requestId, "PLACE");
  }

  private static ExchangeCommand command(String marketId, UUID accountId, UUID requestId, String operation) {
    return new ExchangeCommand(marketId, accountId, requestId, operation);
  }

  private static RequestResultStore resultStore() {
    Map<RequestKey, CommandResult> results = new ConcurrentHashMap<>();
    return new RequestResultStore() {
      @Override
      public Optional<CommandResult> find(UUID accountId, UUID requestId) {
        return Optional.ofNullable(results.get(new RequestKey(accountId, requestId)));
      }

      @Override
      public CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result) {
        return results.computeIfAbsent(new RequestKey(accountId, requestId), ignored -> result);
      }
    };
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(1, TimeUnit.SECONDS)) {
        throw new AssertionError("timed out waiting for test permit");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record RequestKey(UUID accountId, UUID requestId) {}
}
