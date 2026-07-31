package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Locale;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

  @Test
  void mergesPackagedDefaultsWithExistingDiskOverrides() throws Exception {
    File defaults = Files.createTempFile("exchange-default-messages-", ".yml").toFile();
    File overrides = Files.createTempFile("exchange-custom-messages-", ".yml").toFile();
    Files.writeString(defaults.toPath(), """
        en-US:
          existing: Default existing
          handbook-title: Default handbook
        zh-CN:
          existing: 默认消息
          handbook-title: 默认手册
        """);
    Files.writeString(overrides.toPath(), """
        en-US:
          existing: Custom existing
        zh-CN:
          existing: 自定义消息
        """);

    AddonMessageService messages = AddonMessageService.load(defaults, overrides);

    assertThat(messages.message("existing", Locale.US)).isEqualTo("Custom existing");
    assertThat(messages.message("existing", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("自定义消息");
    assertThat(messages.message("handbook-title", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("默认手册");
  }

  @Test
  void bundledEnglishFallbackSurvivesOverridesWithoutEnglishSection() throws Exception {
    byte[] defaults = """
        en-US:
          handbook-title: Default handbook
        zh-CN:
          handbook-title: 默认手册
        """.getBytes(StandardCharsets.UTF_8);
    File overrides = Files.createTempFile("exchange-zh-messages-", ".yml").toFile();
    Files.writeString(overrides.toPath(), """
        zh-CN:
          handbook-title: 自定义手册
        """);

    AddonMessageService messages = AddonMessageService.load(
        new ByteArrayInputStream(defaults), overrides);

    assertThat(messages.message("handbook-title", Locale.US))
        .isEqualTo("Default handbook");
    assertThat(messages.message("handbook-title", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("自定义手册");
  }

  @Test
  void loadsBundledLocalesFromYaml() {
    AddonMessageService messages = AddonMessageService.load(
        new File("src/main/resources/messages.yml"));

    assertThat(messages.message("permission-denied", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("你没有执行此交易所操作的权限。");
    assertThat(messages.message("ui-history-trade-title", Locale.forLanguageTag("zh-CN"),
        "diamond/default", "100.00")).isEqualTo("diamond/default 成交 @ 100.00");
    assertThat(messages.message("ui-confirm-order-title", Locale.forLanguageTag("zh-CN"),
        "LIMIT")).isEqualTo("确认 LIMIT 订单");
    assertThat(messages.message("ui-confirm-request", Locale.US, "abc-123"))
        .isEqualTo("Request: abc-123");
    assertThat(messages.message("ui-confirm-submit-failed", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("交易请求提交失败，请稍后重试。");
    assertThat(messages.message("ui-confirm-submit-result", Locale.US, "ACCEPTED", "order-1"))
        .isEqualTo("Exchange ACCEPTED: order-1");
    assertThat(messages.message("ui-guide-markets", Locale.forLanguageTag("zh-CN")))
        .contains("选择市场")
        .contains("资产");
    assertThat(messages.message("ui-empty-orders", Locale.US))
        .contains("no open orders");
    assertThat(messages.message("ui-nav-assets", Locale.forLanguageTag("zh-CN")))
        .isEqualTo("资产与托管");
    assertThat(messages.message("ui-confirm-cancel-action", Locale.US))
        .isEqualTo("Go back without submitting");
    assertThat(messages.message("ui-guide-admin", Locale.forLanguageTag("zh-CN")))
        .contains("命令")
        .contains("权限");
    assertThat(messages.message("ui-admin-market", Locale.US))
        .isEqualTo("Market controls");
  }
}
