package com.ghostchu.quickshop.addon.exchange.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ExchangeRuntimeFactoryTest {
  @Test
  void acceptsOnlyARegularSQLiteFileUnderTheAddonDataFolder() throws Exception {
    Path dataFolder = Files.createTempDirectory("quickshop-exchange-data-");
    Path database = dataFolder.resolve("exchange.sqlite");

    assertThat(ExchangeRuntimeFactory.requireLocalSqlitePath(
        dataFolder, "jdbc:sqlite:" + database)).isEqualTo(database.toAbsolutePath());
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireLocalSqlitePath(dataFolder, "jdbc:sqlite::memory:"));
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireLocalSqlitePath(dataFolder, "jdbc:sqlite:/tmp/shared.sqlite"));
  }
}
