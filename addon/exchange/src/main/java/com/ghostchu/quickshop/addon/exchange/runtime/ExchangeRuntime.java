package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates writer ownership, recovery and orderly dispatcher shutdown. */
public final class ExchangeRuntime implements AutoCloseable {
  private final SingleWriterGuard writer;
  private final CheckedRunnable recoverBooks;
  private final CheckedRunnable recoverTransfers;
  private final AutoCloseable dispatcher;
  private final CheckedRunnable onLockLost;
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
    this.writer = Objects.requireNonNull(writer, "writer");
    this.recoverBooks = Objects.requireNonNull(recoverBooks, "recoverBooks");
    this.recoverTransfers = Objects.requireNonNull(recoverTransfers, "recoverTransfers");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.onLockLost = Objects.requireNonNull(onLockLost, "onLockLost");
    writer.onLockLost(this::fenceAfterLockLoss);
  }

  public void start() throws Exception {
    writer.acquire();
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
      writer.close();
    }
  }

  @FunctionalInterface
  public interface CheckedRunnable {
    void run() throws Exception;
  }
}
