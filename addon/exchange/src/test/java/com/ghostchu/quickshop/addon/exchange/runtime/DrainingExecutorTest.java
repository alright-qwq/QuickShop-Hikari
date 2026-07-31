package com.ghostchu.quickshop.addon.exchange.runtime;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrainingExecutorTest {
  @Test
  void closeWaitsForAcceptedRecoveryAndRejectsLateWork() throws Exception {
    DrainingExecutor executor = new DrainingExecutor(
        "qs-exchange-recovery-test-", Duration.ofSeconds(2));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.execute(() -> {
      started.countDown();
      try {
        assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      }
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    Thread closer = Thread.ofPlatform().start(executor::close);
    Thread.sleep(50L);
    assertThat(closer.isAlive()).isTrue();
    release.countDown();
    closer.join(2_000L);

    assertThat(closer.isAlive()).isFalse();
    assertThatThrownBy(() -> executor.execute(() -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void timedOutCloseKeepsQueuedWorkForALaterRetry() throws Exception {
    DrainingExecutor executor = new DrainingExecutor(
        "qs-exchange-retry-test-", Duration.ofMillis(25));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch queuedCompleted = new CountDownLatch(1);
    executor.execute(() -> {
      started.countDown();
      boolean released = false;
      while (!released) {
        try {
          released = release.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          // A graceful close must not interrupt already accepted work.
        }
      }
    });
    executor.execute(queuedCompleted::countDown);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(executor::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");
    assertThatThrownBy(() -> executor.execute(() -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");

    release.countDown();
    executor.close();

    assertThat(queuedCompleted.await(2, TimeUnit.SECONDS)).isTrue();
  }
}
