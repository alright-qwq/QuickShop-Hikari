package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates writer ownership, recovery and orderly dispatcher shutdown. */
public final class ExchangeRuntime implements AutoCloseable {
  private final SingleWriterGuard writer;
  private final CheckedRunnable recoverBooks;
  private final TransferRecoveryService transfers;
  private final MarketDispatcher dispatcher;
  private final AtomicBoolean acceptingWrites = new AtomicBoolean();

  public ExchangeRuntime(SingleWriterGuard writer, CheckedRunnable recoverBooks,
                         TransferRecoveryService transfers, MarketDispatcher dispatcher) {
    this.writer = Objects.requireNonNull(writer, "writer");
    this.recoverBooks = Objects.requireNonNull(recoverBooks, "recoverBooks");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  public void start() throws Exception {
    writer.acquire();
    try {
      recoverBooks.run();
      transfers.recoverAllMoneyTransfers();
      acceptingWrites.set(true);
    } catch (Exception failure) {
      writer.close();
      throw failure;
    }
  }

  public boolean acceptingWrites() {
    return acceptingWrites.get() && writer.held();
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
