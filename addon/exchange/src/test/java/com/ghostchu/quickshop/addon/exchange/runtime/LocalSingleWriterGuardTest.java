package com.ghostchu.quickshop.addon.exchange.runtime;

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
}
