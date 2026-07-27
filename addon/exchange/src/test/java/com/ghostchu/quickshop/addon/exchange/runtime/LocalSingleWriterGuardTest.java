package com.ghostchu.quickshop.addon.exchange.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalSingleWriterGuardTest {
  @Test
  void rejectsSecondAcquireUntilReleased() throws Exception {
    LocalSingleWriterGuard guard = new LocalSingleWriterGuard();

    guard.acquire();

    assertThat(guard.held()).isTrue();
    assertThatThrownBy(guard::acquire).isInstanceOf(IllegalStateException.class);
    guard.close();
    guard.acquire();
    assertThat(guard.held()).isTrue();
  }

  @Test
  void usesAnOperatingSystemLockForTheConfiguredLocalDatabase() throws Exception {
    Path database = Files.createTempFile("quickshop-exchange-writer-", ".sqlite");
    LocalSingleWriterGuard first = new LocalSingleWriterGuard(database);
    LocalSingleWriterGuard second = new LocalSingleWriterGuard(database);

    first.acquire();

    assertThatThrownBy(second::acquire).isInstanceOf(IllegalStateException.class);
    first.close();
    second.acquire();
    assertThat(second.held()).isTrue();
    second.close();
  }
}
