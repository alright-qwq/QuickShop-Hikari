package com.ghostchu.quickshop.addon.exchange.runtime;

public interface SingleWriterGuard extends AutoCloseable {
  void acquire() throws Exception;

  boolean held();

  /** Called exactly once when a held distributed lock can no longer be trusted. */
  default void onLockLost(Runnable action) {
    // Local guards cannot lose a held operating-system lock while this process remains alive.
  }

  @Override
  void close() throws Exception;
}
