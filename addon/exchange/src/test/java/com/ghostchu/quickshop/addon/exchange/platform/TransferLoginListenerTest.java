package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferLoginListenerTest {
  @Test
  void delegatesLoginRecoveryToTheWriterFencedSubmitter() {
    AtomicReference<UUID> recovered = new AtomicReference<>();
    TransferLoginListener listener = new TransferLoginListener(accountId -> {
      recovered.set(accountId);
      return CompletableFuture.completedFuture(null);
    });
    UUID accountId = UUID.randomUUID();

    listener.recover(accountId);

    assertThat(recovered).hasValue(accountId);
  }

  @Test
  void reportsAsynchronousRecoveryFailureWithTheAccountId() {
    AtomicReference<UUID> failedAccount = new AtomicReference<>();
    AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
    BiConsumer<UUID, Throwable> reporter = (accountId, failure) -> {
      failedAccount.set(accountId);
      reportedFailure.set(failure);
    };
    TransferLoginListener listener = new TransferLoginListener(
        accountId -> CompletableFuture.failedFuture(new IllegalStateException("recovery failed")),
        reporter);
    UUID accountId = UUID.randomUUID();

    listener.recover(accountId);

    assertThat(failedAccount).hasValue(accountId);
    assertThat(reportedFailure.get()).hasMessage("recovery failed");
  }

  @Test
  void reportsSynchronousRecoverySubmissionFailure() {
    AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
    TransferLoginListener listener = new TransferLoginListener(accountId -> {
      throw new IllegalStateException("writer executor rejected recovery");
    }, (accountId, failure) -> reportedFailure.set(failure));

    listener.recover(UUID.randomUUID());

    assertThat(reportedFailure.get()).hasMessage("writer executor rejected recovery");
  }
}
