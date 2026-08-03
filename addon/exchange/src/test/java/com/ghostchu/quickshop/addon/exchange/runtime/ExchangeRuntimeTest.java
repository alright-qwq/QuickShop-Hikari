package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.display.DisplayScheduler;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartCache;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartRenderer;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartSeriesBuilder;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayRegistry;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayService;
import com.ghostchu.quickshop.addon.exchange.display.MarketSignFormatter;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeRequestSubmitter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeRuntimeTest {
  @Test
  void acceptsWritesOnlyAfterRecoveryAndClosesDispatcherBeforeWriter() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true));

    assertThat(runtime.acceptingWrites()).isFalse();
    runtime.start();
    assertThat(runtime.acceptingWrites()).isTrue();

    runtime.close();

    assertThat(dispatcherClosed).isTrue();
    assertThat(writer.held()).isFalse();
    assertThat(writer.closedAfterDispatcher()).isTrue();
  }

  @Test
  void fencesNewWritesImmediatelyWhenTheWriterLockIsLost() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean marketsRecovering = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {},
        () -> marketsRecovering.set(true));

    runtime.start();
    writer.loseLock();

    assertThat(runtime.acceptingWrites()).isFalse();
    assertThat(marketsRecovering).isTrue();
  }

  @Test
  void completesRecoveryWhenFactoryAlreadyOwnsTheWriterLock() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean recovered = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> recovered.set(true), () -> {}, () -> {});
    writer.acquire();

    runtime.start();

    assertThat(recovered).isTrue();
    assertThat(runtime.acceptingWrites()).isTrue();
    runtime.close();
  }

  @Test
  void keepsStartupRecoveryInsideTheWriterFence() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    AtomicBoolean bookRecoveryFenced = new AtomicBoolean();
    AtomicBoolean transferRecoveryFenced = new AtomicBoolean();
    ExchangeRuntime runtime = new ExchangeRuntime(writer,
        () -> bookRecoveryFenced.set(writer.runningGuardedWork()),
        () -> transferRecoveryFenced.set(writer.runningGuardedWork()),
        () -> {});

    runtime.start();

    assertThat(bookRecoveryFenced).isTrue();
    assertThat(transferRecoveryFenced).isTrue();
    runtime.close();
  }

  @Test
  void flushesOperationalDataAfterDispatcherDrainAndBeforeWriterRelease() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    AtomicBoolean flushedAfterDispatcher = new AtomicBoolean();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true), () -> {},
        () -> flushedAfterDispatcher.set(dispatcherClosed.get()));

    runtime.start();
    runtime.close();

    assertThat(flushedAfterDispatcher).isTrue();
    assertThat(writer.closedAfterDispatcher()).isTrue();
  }

  @Test
  void retriesOperationalDrainBeforeReleasingWriter() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger drainAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherClosed.set(true), () -> {}, () -> {
          if (drainAttempts.incrementAndGet() == 1) {
            throw new IllegalStateException("drain failed");
          }
        });
    runtime.start();

    assertThatThrownBy(runtime::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("drain failed");
    assertThat(runtime.acceptingWrites()).isFalse();
    assertThat(runtime.closed()).isFalse();
    assertThat(writer.held()).isTrue();

    runtime.close();

    assertThat(drainAttempts).hasValue(2);
    assertThat(runtime.closed()).isTrue();
    assertThat(writer.held()).isFalse();
    assertThat(writer.closeCalls()).isEqualTo(1);
  }

  @Test
  void retriesDispatcherDrainBeforeRunningLaterShutdownPhases() throws Exception {
    AtomicBoolean dispatcherClosed = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger dispatcherAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger finalFlushes =
        new java.util.concurrent.atomic.AtomicInteger();
    TrackingGuard writer = new TrackingGuard(dispatcherClosed);
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {
      if (dispatcherAttempts.incrementAndGet() == 1) {
        throw new IllegalStateException("dispatcher drain failed");
      }
      dispatcherClosed.set(true);
    }, () -> {}, finalFlushes::incrementAndGet);
    runtime.start();

    assertThatThrownBy(runtime::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dispatcher drain failed");
    assertThat(finalFlushes).hasValue(0);
    assertThat(writer.held()).isTrue();

    runtime.close();

    assertThat(dispatcherAttempts).hasValue(2);
    assertThat(finalFlushes).hasValue(1);
    assertThat(runtime.closed()).isTrue();
    assertThat(writer.closeCalls()).isEqualTo(1);
  }

  @Test
  void stopsPublicWebBeforeDisplaysAndLaterShutdownPhases() throws Exception {
    java.util.List<String> phases = new java.util.ArrayList<>();
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean(), () -> phases.add("writer"));
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> phases.add("dispatcher"), () -> {}, () -> phases.add("operational"),
        null, null, null, null, null, null, () -> phases.add("displays"), () -> {},
        () -> phases.add("web"));

    runtime.start();
    runtime.close();
    runtime.close();

    assertThat(phases).containsExactly("web", "displays", "dispatcher", "operational", "writer");
  }

  @Test
  void retriesPublicWebShutdownBeforeRunningLaterShutdownPhases() throws Exception {
    java.util.concurrent.atomic.AtomicInteger webAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger displayAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {},
        () -> {}, () -> {}, null, null, null, null, null, null,
        displayAttempts::incrementAndGet, () -> {}, () -> {
          if (webAttempts.incrementAndGet() == 1) {
            throw new IllegalStateException("web shutdown failed");
          }
        });
    runtime.start();

    assertThatThrownBy(runtime::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("web shutdown failed");
    assertThat(displayAttempts).hasValue(0);
    assertThat(writer.held()).isTrue();

    runtime.close();

    assertThat(webAttempts).hasValue(2);
    assertThat(displayAttempts).hasValue(1);
    assertThat(runtime.closed()).isTrue();
  }

  @Test
  void stopsDisplaysAndSavesBindingsBeforeDispatcherDrain() throws Exception {
    java.util.List<String> phases = new java.util.ArrayList<>();
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    MarketDisplayRegistry registry = MarketDisplayRegistry.load(
        Files.createTempDirectory("exchange-runtime-displays-").resolve("displays.yml"));
    MarketDisplayService displays = displayService();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> phases.add("dispatcher"), () -> {}, () -> phases.add("operational"),
        null, null, null, null, displays, () -> {
          displays.close();
          phases.add("displays");
          phases.add("registry");
          registry.save();
        });

    runtime.start();
    runtime.close();

    assertThat(phases).containsExactly("displays", "registry", "dispatcher", "operational");
  }

  @Test
  void retriesDisplayShutdownBeforeRunningLaterShutdownPhases() throws Exception {
    java.util.concurrent.atomic.AtomicInteger displayAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger dispatcherAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {},
        () -> dispatcherAttempts.incrementAndGet(), () -> {}, () -> {},
        null, null, null, null, null, () -> {
          if (displayAttempts.incrementAndGet() == 1) {
            throw new IllegalStateException("display shutdown failed");
          }
        });
    runtime.start();

    assertThatThrownBy(runtime::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("display shutdown failed");
    assertThat(dispatcherAttempts).hasValue(0);
    assertThat(writer.held()).isTrue();

    runtime.close();

    assertThat(displayAttempts).hasValue(2);
    assertThat(dispatcherAttempts).hasValue(1);
    assertThat(runtime.closed()).isTrue();
  }

  @Test
  void exposesConfiguredDisplayService() {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    MarketDisplayService displays = displayService();
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {},
        () -> {}, () -> {}, null, null, null, null, displays, () -> {});

    assertThat(runtime.displays()).isSameAs(displays);
  }

  @Test
  void returnsWriteResultOnlyWhileTheWriterFenceIsHeld() throws Exception {
    TrackingGuard writer = new TrackingGuard(new AtomicBoolean());
    ExchangeRuntime runtime = new ExchangeRuntime(writer, () -> {}, () -> {}, () -> {});

    assertThat(runtime.callWhileWriting(() -> "not-run")).isEmpty();
    runtime.start();
    assertThat(runtime.callWhileWriting(() -> "committed")).contains("committed");

    writer.loseLock();
    assertThat(runtime.callWhileWriting(() -> "not-run")).isEmpty();
  }

  @Test
  void holdsWriterFenceUntilAnAsynchronousMutationCompletes() throws Exception {
    var database = Files.createTempFile("exchange-async-writer-", ".sqlite");
    LocalSingleWriterGuard first = new LocalSingleWriterGuard(database);
    LocalSingleWriterGuard second = new LocalSingleWriterGuard(database);
    ExchangeRuntime runtime = new ExchangeRuntime(first, () -> {}, () -> {}, () -> {});
    runtime.start();
    CountDownLatch started = new CountDownLatch(1);
    CompletableFuture<String> mutation = new CompletableFuture<>();

    CompletableFuture<java.util.Optional<String>> fenced = runtime.callAsyncWhileWriting(() -> {
      started.countDown();
      return mutation;
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    Thread closer = Thread.ofPlatform().start(() -> {
      try {
        runtime.close();
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    Thread.sleep(50L);

    assertThat(closer.isAlive()).isTrue();
    assertThatThrownBy(second::acquire).isInstanceOf(IllegalStateException.class);
    mutation.complete("completed");
    assertThat(fenced.join()).contains("completed");
    closer.join(2_000L);
    second.acquire();
    second.close();
  }

  @Test
  void rejectsGuiConfirmationWhenWriterIsUnavailableWithoutChangingRequestId() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});
    UUID requestId = UUID.randomUUID();
    ExchangeMenuRequest request = ExchangeMenuRequest.order(new ExchangeMenuRequest.OrderDraft(
        requestId, UUID.randomUUID(), "diamond-usd", OrderSide.BUY, OrderType.LIMIT,
        new BigDecimal("100.00"), null, 1));

    ExchangeRequestSubmitter.SubmissionResult result =
        new RuntimeExchangeRequestSubmitter(runtime, Runnable::run).submit(request).join();

    assertThat(result.requestId()).isEqualTo(requestId);
    assertThat(result.outcome()).isEqualTo(ExchangeRequestSubmitter.Outcome.REJECTED);
    assertThat(result.reference()).isEqualTo("writer unavailable");
  }

  @Test
  void rejectsNonConfirmableRequestInsteadOfInventingRequestId() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});

    assertThatThrownBy(() -> new RuntimeExchangeRequestSubmitter(runtime, Runnable::run)
        .submit(ExchangeMenuRequest.page("markets")).join())
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasRootCauseMessage("request is not confirmable");
  }

  @Test
  void closingTheGuiSubmitterRejectsLateConfirmations() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});
    RuntimeExchangeRequestSubmitter submitter = new RuntimeExchangeRequestSubmitter(runtime);
    ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    submitter.close();

    assertThatThrownBy(() -> submitter.submit(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void retriesOwnedExecutorCloseWithoutReopeningSubmissions() {
    ExchangeRuntime runtime = new ExchangeRuntime(new TrackingGuard(new AtomicBoolean()),
        () -> {}, () -> {}, () -> {});
    java.util.concurrent.atomic.AtomicInteger closeAttempts =
        new java.util.concurrent.atomic.AtomicInteger();
    AutoCloseable owner = () -> {
      if (closeAttempts.incrementAndGet() == 1) {
        throw new IllegalStateException("executor drain timed out");
      }
    };
    RuntimeExchangeRequestSubmitter submitter =
        new RuntimeExchangeRequestSubmitter(runtime, Runnable::run, owner);
    ExchangeMenuRequest request = ExchangeMenuRequest.cancel(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    assertThatThrownBy(submitter::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("executor drain timed out");
    assertThatThrownBy(() -> submitter.submit(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");

    submitter.close();

    assertThat(closeAttempts).hasValue(2);
  }

  private static MarketDisplayService displayService() {
    DisplayScheduler scheduler = new DisplayScheduler() {
      @Override
      public CompletableFuture<Void> updateMapFrame(
          com.ghostchu.quickshop.addon.exchange.display.MapFrameBinding frame,
          com.ghostchu.quickshop.addon.exchange.display.MarketChartImage image) {
        return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletableFuture<Void> updateSign(
          com.ghostchu.quickshop.addon.exchange.display.MarketSignBinding sign,
          com.ghostchu.quickshop.addon.exchange.display.MarketSignLines lines) {
        return CompletableFuture.completedFuture(null);
      }
    };
    return new MarketDisplayService((marketId, period, toExclusive) ->
        CompletableFuture.failedFuture(new AssertionError("unexpected snapshot")),
        new MarketChartSeriesBuilder(), new MarketChartRenderer(), new MarketChartCache(1),
        new MarketSignFormatter(), scheduler, java.time.Clock.systemUTC());
  }

  private static final class TrackingGuard implements SingleWriterGuard {
    private final AtomicBoolean dispatcherClosed;
    private boolean held;
    private boolean closedAfterDispatcher;
    private boolean runningGuardedWork;
    private int closeCalls;
    private Runnable onLockLost = () -> {};

    private final Runnable onClose;

    private TrackingGuard(AtomicBoolean dispatcherClosed) {
      this(dispatcherClosed, () -> {});
    }

    private TrackingGuard(AtomicBoolean dispatcherClosed, Runnable onClose) {
      this.dispatcherClosed = dispatcherClosed;
      this.onClose = onClose;
    }

    @Override
    public void acquire() {
      if (held) {
        throw new IllegalStateException("exchange writer lock is already held");
      }
      held = true;
    }

    @Override
    public boolean held() {
      return held;
    }

    @Override
    public boolean runWhileHeld(GuardedWork work) throws Exception {
      if (!held) {
        return false;
      }
      runningGuardedWork = true;
      try {
        work.run();
        return true;
      } finally {
        runningGuardedWork = false;
      }
    }

    @Override
    public void close() {
      closeCalls++;
      closedAfterDispatcher = dispatcherClosed.get();
      onClose.run();
      held = false;
    }

    @Override
    public void onLockLost(Runnable action) {
      onLockLost = action;
    }

    private void loseLock() {
      held = false;
      onLockLost.run();
    }

    private boolean closedAfterDispatcher() {
      return closedAfterDispatcher;
    }

    private int closeCalls() {
      return closeCalls;
    }

    private boolean runningGuardedWork() {
      return runningGuardedWork;
    }
  }
}
