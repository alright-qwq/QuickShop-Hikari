package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.persistence.TransactionFence;

public interface SingleWriterGuard extends AutoCloseable {
  void acquire() throws Exception;

  boolean held();

  /** Activates the transaction-level database fence for this ownership generation. */
  default TransactionFence activateTransactionFence(TableNames tables) throws Exception {
    return TransactionFence.NONE;
  }

  /**
   * Runs work while the writer ownership remains fenced from a concurrent lock-loss callback.
   * Returns false when the guard was already unavailable.
   */
  default boolean runWhileHeld(GuardedWork work) throws Exception {
    if (!held()) {
      return false;
    }
    work.run();
    return true;
  }

  /** Called exactly once when a held distributed lock can no longer be trusted. */
  default void onLockLost(Runnable action) {
    // Local guards cannot lose a held operating-system lock while this process remains alive.
  }

  @Override
  void close() throws Exception;

  @FunctionalInterface
  interface GuardedWork {
    void run() throws Exception;
  }
}
