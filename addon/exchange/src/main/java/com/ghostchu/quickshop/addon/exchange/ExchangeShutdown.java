package com.ghostchu.quickshop.addon.exchange;

/** Runs independent plugin shutdown phases without allowing one failure to skip later cleanup. */
final class ExchangeShutdown {
  private ExchangeShutdown() {}

  static Result close(RuntimeHandle runtime, CheckedClose... entrypointCleanup) {
    Throwable failure = null;
    for (CheckedClose cleanup : entrypointCleanup) {
      try {
        cleanup.close();
      } catch (Throwable cleanupFailure) {
        failure = append(failure, cleanupFailure);
      }
    }
    if (runtime != null) {
      try {
        runtime.close();
      } catch (Throwable runtimeFailure) {
        failure = append(failure, runtimeFailure);
      }
    }
    return new Result(failure, runtime == null || runtime.closed());
  }

  private static Throwable append(Throwable failure, Throwable next) {
    if (failure == null) {
      return next;
    }
    if (failure != next) {
      failure.addSuppressed(next);
    }
    return failure;
  }

  record Result(Throwable failure, boolean runtimeClosed) {}

  interface RuntimeHandle extends AutoCloseable {
    @Override
    void close() throws Exception;

    boolean closed();
  }

  @FunctionalInterface
  interface CheckedClose {
    void close() throws Exception;
  }
}
