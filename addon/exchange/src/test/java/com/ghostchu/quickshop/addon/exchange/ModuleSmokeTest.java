package com.ghostchu.quickshop.addon.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSmokeTest {
  @Test
  void exposesAddonMainClass() {
    assertThat(Main.class.getName())
        .isEqualTo("com.ghostchu.quickshop.addon.exchange.Main");
  }

  @Test
  void includesDefaultConfigOnClasspath() {
    assertThat(Main.class.getResource("/config.yml"))
        .isNotNull();
  }
}
