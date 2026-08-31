package com.ghostchu.quickshop.addon.exchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {
  @Test
  void detectsBrokenYamlBeforeBukkitSilentlyDropsTheConfiguration(@TempDir Path folder)
      throws IOException {
    Path valid = folder.resolve("valid.yml");
    Path broken = folder.resolve("broken.yml");
    Path missing = folder.resolve("missing.yml");
    Files.writeString(valid, "enabled: true\n");
    Files.writeString(broken, "enabled: [true\n");

    assertThat(Main.yamlSyntaxError(valid.toFile())).isNull();
    assertThat(Main.yamlSyntaxError(broken.toFile())).isNotBlank();
    assertThat(Main.yamlSyntaxError(missing.toFile())).isNotBlank();
  }
}
