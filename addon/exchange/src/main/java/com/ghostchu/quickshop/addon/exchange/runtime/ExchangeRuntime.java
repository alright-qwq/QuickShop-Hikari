package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates writer ownership, recovery and orderly dispatcher shutdown. */
public final class ExchangeRuntime implements AutoCloseable {
  private final SingleWriterGuard writer;
  private final CheckedRunnable recoverBooks;
  private final CheckedRunnable recoverTransfers;
  private final AutoCloseable dispatcher;
  private final CheckedRunnable onLockLost;
  private final CheckedRunnable afterDispatcherClosed;
  private final ExchangeViewService views;
  private final AtomicBoolean acceptingWrites = new AtomicBoolean();

  public ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                         TransferRecoveryService transfers, MarketDispatcher dispatcher) {
    this(writer, recoverBooks, transfers::recoverAllMoneyTransfers, dispatcher, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, () -> {});
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed) {
    this(writer, recoverBooks, recoverTransfers, dispatcher, onLockLost, afterDispatcherClosed, null);
  }

  ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                  CheckedRunnable recoverTransfers, AutoCloseable dispatcher,
                  CheckedRunnable onLockLost, CheckedRunnable afterDispatcherClosed,
                  ExchangeViewService views) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.recoverBooks = Objects.requireNonNull(recoverBooks, "recoverBooks");
    this.recoverTransfers = Objects.requireNonNull(recoverTransfers, "recoverTransfers");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.onLockLost = Objects.requireNonNull(onLockLost, "onLockLost");
    this.afterDispatcherClosed = Objects.requireNonNull(afterDispatcherClosed,
        "afterDispatcherClosed");
    this.views = views;
    writer.onLockLost(this::fenceAfterLockLoss);
  }

  public void start() throws Exception {
    if (!writer.held()) {
      writer.acquire();
    }
    try {
      recoverBooks.run();
      recoverTransfers.run();
      acceptingWrites.set(true);
    } catch (Exception failure) {
      writer.close();
      throw failure;
    }
  }

  public boolean acceptingWrites() {
    return acceptingWrites.get() && writer.held();
  }

  public ExchangeViewService views() {
    if (views == null) {
      throw new IllegalStateException("runtime views are not configured");
    }
    return views;
  }

  private void fenceAfterLockLoss() {
    acceptingWrites.set(false);
    try {
      onLockLost.run();
    } catch (Exception ignored) {
      // The write fence is already active; retry and operator recovery happen on restart.
    }
  }

  @Override
  public void close() throws Exception {
    acceptingWrites.set(false);
    try {
      dispatcher.close();
    } finally {
      try {
        afterDispatcherClosed.run();
      } finally {
        writer.close();
      }
    }
  }

  @FunctionalInterface
  public interface CheckedRunnable {
    void run() throws Exception;
  }
}
