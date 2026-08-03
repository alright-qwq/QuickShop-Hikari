package com.ghostchu.quickshop.addon.exchange;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSmokeTest {
  @Test
  void exposesAddonMainClass() {
    assertThat(Main.class.getName())
        .isEqualTo("com.ghostchu.quickshop.addon.exchange.Main");
  }

  @Test
  void includesDefaultConfigFromExchangeOutput() {
    URL codeSourceUrl = Main.class.getProtectionDomain().getCodeSource().getLocation();
    URL configUrl = Main.class.getResource("/config.yml");

    assertThat(codeSourceUrl)
        .isNotNull();
    assertThat(configUrl)
        .isNotNull();
    assertThat(configUrl.toExternalForm())
        .startsWith(codeSourceUrl.toExternalForm());
  }

  @Test
  void packagesAllFirstRunConfigurationResources() {
    assertThat(Main.class.getResource("/markets.yml")).isNotNull();
    assertThat(Main.class.getResource("/messages.yml")).isNotNull();
    assertThat(Main.firstRunResources()).containsExactly("markets.yml", "messages.yml");
  }

  @Test
  void packagesHandbookAndClockContracts() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/config.yml").toURI()));
    YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/plugin.yml").toURI()));
    YamlConfiguration messages = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/messages.yml").toURI()));

    assertThat(config.getBoolean("gui.clock.enabled")).isTrue();
    assertThat(config.getString("gui.clock.zone-id")).isEqualTo("Asia/Shanghai");
    assertThat(config.getString("gui.clock.format")).isEqualTo("yyyy-MM-dd HH:mm");
    assertThat(config.getBoolean("handbook.enabled")).isTrue();
    assertThat(config.getBoolean("handbook.self-claim")).isTrue();
    assertThat(config.getBoolean("handbook.prevent-duplicate")).isTrue();
    assertThat(config.getString("handbook.material")).isEqualTo("KNOWLEDGE_BOOK");
    assertThat(plugin.getString("permissions.quickshop.exchange.admin.handbook.default"))
        .isEqualTo("op");
    for (String locale : java.util.List.of("en-US", "zh-CN")) {
      assertThat(messages.getString(locale + ".ui-clock-title")).isNotBlank();
      assertThat(messages.getString(locale + ".ui-clock-zone")).isNotBlank();
      assertThat(messages.getString(locale + ".handbook-title")).isNotBlank();
      assertThat(messages.getString(locale + ".handbook-lore-1")).isNotBlank();
      assertThat(messages.getString(locale + ".handbook-lore-2")).isNotBlank();
      assertThat(messages.getString(locale + ".handbook-claim-success")).isNotBlank();
      assertThat(messages.getString(locale + ".admin-handbook-given")).isNotBlank();
      assertThat(messages.getString(locale + ".admin-handbook-player-not-found")).isNotBlank();
    }
  }

  @Test
  void packagesSafePublicMarketWebDefaults() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/config.yml").toURI()));

    assertThat(config.getBoolean("web-api.enabled")).isFalse();
    assertThat(config.getString("web-api.bind-address")).isEqualTo("127.0.0.1");
    assertThat(config.getInt("web-api.port")).isEqualTo(8765);
    assertThat(config.getLong("web-api.cache-seconds")).isEqualTo(3L);
    assertThat(config.getInt("web-api.threads")).isEqualTo(2);
    assertThat(config.getInt("web-api.maximum-concurrent-requests")).isEqualTo(16);
    assertThat(com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod.values())
        .extracting(com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod::token)
        .containsExactly("1h", "6h", "24h", "7d");
  }

  @Test
  void packagesDisplayDefaultsPermissionAndLocalizedMessages() throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/config.yml").toURI()));
    YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/plugin.yml").toURI()));
    YamlConfiguration messages = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/messages.yml").toURI()));

    assertThat(config.getBoolean("displays.enabled")).isTrue();
    assertThat(config.getLong("displays.refresh-seconds")).isEqualTo(5L);
    assertThat(config.getInt("displays.max-map-walls")).isEqualTo(128);
    assertThat(config.getInt("displays.max-signs")).isEqualTo(256);
    assertThat(plugin.getString("permissions.quickshop.exchange.admin.display.default"))
        .isEqualTo("op");
    assertThat(messages.getString("en-US.display-map-created")).isNotBlank();
    assertThat(messages.getString("zh-CN.display-map-created")).isNotBlank();
    assertThat(messages.getString("zh-CN.display-operation-failed")).isNotBlank();
  }

  @Test
  void packagesReloadPermissionAndLocalizedMessages() throws Exception {
    YamlConfiguration plugin = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/plugin.yml").toURI()));
    YamlConfiguration messages = YamlConfiguration.loadConfiguration(new File(
        Main.class.getResource("/messages.yml").toURI()));

    assertThat(plugin.getString("permissions.quickshop.exchange.admin.reload.default"))
        .isEqualTo("op");
    assertThat(messages.getString("en-US.admin-reload-completed")).isNotBlank();
    assertThat(messages.getString("zh-CN.admin-reload-completed")).isNotBlank();
  }
}
