package com.ghostchu.quickshop.addon.exchange.transfer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerOperationSerialiserTest {
  @Test
  void serialisesOperationsForTheSameAccount() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 2, 8);
    UUID accountId = UUID.randomUUID();
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    List<String> order = new CopyOnWriteArrayList<>();
    var first = serialiser.submit(accountId, () -> {
      order.add("first-start");
      firstStarted.countDown();
      await(releaseFirst);
      order.add("first-end");
      return "first";
    });
    assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

    var second = serialiser.submit(accountId, () -> {
      order.add("second");
      return "second";
    });

    assertThat(second).isNotDone();
    releaseFirst.countDown();
    assertThat(first.join()).isEqualTo("first");
    assertThat(second.join()).isEqualTo("second");
    assertThat(order).containsExactly("first-start", "first-end", "second");
    serialiser.close();
  }

  @Test
  void progressesDifferentAccountsConcurrentlyOnBoundedWorkers() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 2, 8);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    var first = serialiser.submit(UUID.randomUUID(), () -> {
      bothStarted.countDown();
      await(release);
      return "first";
    });
    var second = serialiser.submit(UUID.randomUUID(), () -> {
      bothStarted.countDown();
      await(release);
      return "second";
    });

    assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
    release.countDown();
    assertThat(first.join()).isEqualTo("first");
    assertThat(second.join()).isEqualTo("second");
    serialiser.close();
  }

  @Test
  void rejectsNewOperationsWhenBoundedCapacityIsFull() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 1, 1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var accepted = serialiser.submit(UUID.randomUUID(), () -> {
      started.countDown();
      await(release);
      return "accepted";
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(() -> serialiser.submit(UUID.randomUUID(), () -> "rejected"))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessageContaining("capacity");

    release.countDown();
    assertThat(accepted.join()).isEqualTo("accepted");
    serialiser.close();
  }

  @Test
  void removesIdleAccountQueuesAfterCompletion() {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 1, 4);

    assertThat(serialiser.submit(UUID.randomUUID(), () -> "done").join()).isEqualTo("done");

    assertThat(serialiser.activeAccountCount()).isZero();
    serialiser.close();
  }

  @Test
  void failedOperationDoesNotBlockTheNextOperationForThatAccount() {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 1, 4);
    UUID accountId = UUID.randomUUID();
    var failed = serialiser.submit(accountId, () -> {
      throw new IllegalStateException("failed");
    });
    var next = serialiser.submit(accountId, () -> "recovered");

    assertThatThrownBy(failed::join).hasRootCauseMessage("failed");
    assertThat(next.join()).isEqualTo("recovered");
    serialiser.close();
  }

  @Test
  void closeWaitsForAcceptedOperationsAndRejectsNewOnes() throws Exception {
    PlayerOperationSerialiser serialiser = new PlayerOperationSerialiser(
        Duration.ofSeconds(2), 2, 8);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var accepted = serialiser.submit(UUID.randomUUID(), () -> {
      started.countDown();
      await(release);
      return "completed";
    });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

    Thread closer = Thread.ofPlatform().start(serialiser::close);
    Thread.sleep(50L);
    assertThat(closer.isAlive()).isTrue();
    assertThatThrownBy(() -> serialiser.submit(UUID.randomUUID(), () -> "late"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    release.countDown();
    closer.join(2_000L);

    assertThat(closer.isAlive()).isFalse();
    assertThat(accepted.join()).isEqualTo("completed");
    assertThat(serialiser.activeAccountCount()).isZero();
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting in test operation");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(failure);
    }
  }
}
