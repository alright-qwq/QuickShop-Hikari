package com.ghostchu.quickshop.addon.exchange.runtime;

public interface SingleWriterGuard extends AutoCloseable {
  void acquire() throws Exception;

  boolean held();

  @Override
  void close() throws Exception;
}
