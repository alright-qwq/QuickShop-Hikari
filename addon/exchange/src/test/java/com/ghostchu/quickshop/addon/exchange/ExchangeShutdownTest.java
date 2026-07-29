package com.ghostchu.quickshop.addon.exchange;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeShutdownTest {
  @Test
  void entrypointFailureStillClosesRuntime() {
    AtomicInteger runtimeCloses = new AtomicInteger();
    ExchangeShutdown.RuntimeHandle runtime = runtime(
        runtimeCloses,
        new AtomicBoolean(),
        null);

    ExchangeShutdown.Result result = ExchangeShutdown.close(runtime,
        () -> {
          throw new IllegalStateException("menu drain failed");
        });

    assertThat(runtimeCloses).hasValue(1);
    assertThat(result.runtimeClosed()).isTrue();
    assertThat(result.failure())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("menu drain failed");
  }

  @Test
  void aggregatesEntrypointAndRuntimeFailures() {
    AtomicInteger runtimeCloses = new AtomicInteger();
    ExchangeShutdown.RuntimeHandle runtime = runtime(
        runtimeCloses,
        new AtomicBoolean(),
        new IllegalStateException("runtime drain failed"));

    ExchangeShutdown.Result result = ExchangeShutdown.close(runtime,
        () -> {
          throw new IllegalArgumentException("menu drain failed");
        },
        () -> {
          throw new IllegalStateException("listener cleanup failed");
        });

    assertThat(runtimeCloses).hasValue(1);
    assertThat(result.runtimeClosed()).isFalse();
    assertThat(result.failure())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("menu drain failed");
    assertThat(result.failure().getSuppressed())
        .extracting(Throwable::getMessage)
        .containsExactly("listener cleanup failed", "runtime drain failed");
  }

  @Test
  void reportsIncompleteRuntimeSoCallerCanRetainItForRetry() {
    AtomicInteger runtimeCloses = new AtomicInteger();
    AtomicBoolean closed = new AtomicBoolean();
    ExchangeShutdown.RuntimeHandle firstAttempt = runtime(
        runtimeCloses,
        closed,
        new IllegalStateException("final flush failed"));

    ExchangeShutdown.Result first = ExchangeShutdown.close(firstAttempt);

    assertThat(first.runtimeClosed()).isFalse();
    assertThat(closed).isFalse();

    ExchangeShutdown.Result retry = ExchangeShutdown.close(runtime(runtimeCloses, closed, null));

    assertThat(retry.failure()).isNull();
    assertThat(retry.runtimeClosed()).isTrue();
    assertThat(runtimeCloses).hasValue(2);
  }

  private static ExchangeShutdown.RuntimeHandle runtime(
      AtomicInteger closes,
      AtomicBoolean closed,
      RuntimeException failure) {
    return new ExchangeShutdown.RuntimeHandle() {
      @Override
      public void close() {
        closes.incrementAndGet();
        if (failure != null) {
          throw failure;
        }
        closed.set(true);
      }

      @Override
      public boolean closed() {
        return closed.get();
      }
    };
  }
}
