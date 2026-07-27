package com.ghostchu.quickshop.addon.exchange.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

  private static final class TrackingGuard implements SingleWriterGuard {
    private final AtomicBoolean dispatcherClosed;
    private boolean held;
    private boolean closedAfterDispatcher;
    private Runnable onLockLost = () -> {};

    private TrackingGuard(AtomicBoolean dispatcherClosed) {
      this.dispatcherClosed = dispatcherClosed;
    }

    @Override
    public void acquire() {
      held = true;
    }

    @Override
    public boolean held() {
      return held;
    }

    @Override
    public void close() {
      closedAfterDispatcher = dispatcherClosed.get();
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
  }
}
