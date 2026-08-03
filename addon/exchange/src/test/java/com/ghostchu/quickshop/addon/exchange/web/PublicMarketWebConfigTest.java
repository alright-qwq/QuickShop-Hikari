package com.ghostchu.quickshop.addon.exchange.web;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PublicMarketWebConfigTest {
  @Test
  void providesSafeLoopbackDefaults() {
    PublicMarketWebConfig config = PublicMarketWebConfig.defaults();

    assertThat(config.enabled()).isFalse();
    assertThat(config.bindAddress()).isEqualTo("127.0.0.1");
    assertThat(config.port()).isEqualTo(8765);
    assertThat(config.cacheDuration()).isEqualTo(Duration.ofSeconds(3));
    assertThat(config.threads()).isEqualTo(2);
    assertThat(config.maximumConcurrentRequests()).isEqualTo(16);
  }

  @Test
  void rejectsPublicBindingAndUnboundedResourceSettings() {
    assertThatIllegalArgumentException().isThrownBy(() ->
        new PublicMarketWebConfig(true, "0.0.0.0", 8765, Duration.ofSeconds(3), 2, 16));
    assertThatIllegalArgumentException().isThrownBy(() ->
        new PublicMarketWebConfig(true, "127.0.0.1", 0, Duration.ofSeconds(3), 2, 16));
    assertThatIllegalArgumentException().isThrownBy(() ->
        new PublicMarketWebConfig(true, "127.0.0.1", 8765, Duration.ZERO, 2, 16));
    assertThatIllegalArgumentException().isThrownBy(() ->
        new PublicMarketWebConfig(true, "127.0.0.1", 8765, Duration.ofSeconds(3), 0, 16));
  }
}
