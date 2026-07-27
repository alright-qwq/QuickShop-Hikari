package com.ghostchu.quickshop.addon.exchange.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local guard for a single addon instance using a local SQLite database. */
public final class LocalSingleWriterGuard implements SingleWriterGuard {
  private final AtomicBoolean held = new AtomicBoolean();

  @Override
  public void acquire() {
    if (!held.compareAndSet(false, true)) {
      throw new IllegalStateException("exchange writer lock is already held");
    }
  }

  @Override
  public boolean held() {
    return held.get();
  }

  @Override
  public void close() {
    held.set(false);
  }
}
