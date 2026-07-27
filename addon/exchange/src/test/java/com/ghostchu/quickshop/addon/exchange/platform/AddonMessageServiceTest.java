package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AddonMessageServiceTest {
  @Test
  void replacesNamedRequestIdPlaceholderAndFallsBackToEnglish() {
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of("request-accepted", "Accepted: <requestId>"),
        "zh-CN", Map.of("request-accepted", "已受理：<requestId>")));

    assertThat(messages.message("request-accepted", Locale.US, "abc-123"))
        .isEqualTo("Accepted: abc-123");
    assertThat(messages.message("request-accepted", Locale.FRANCE, "abc-123"))
        .isEqualTo("Accepted: abc-123");
  }
}
