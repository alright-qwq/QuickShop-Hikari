package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartOptions;
import com.ghostchu.quickshop.addon.exchange.web.PublicMarketWebConfig;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ExchangeRuntimeFactoryTest {
  @Test
  void defaultsToLocalSQLiteWhenDatabaseModeIsMissing() {
    assertThat(ExchangeRuntimeFactory.databaseMode(null)).isEqualTo("sqlite");
    assertThat(ExchangeRuntimeFactory.databaseMode("   ")).isEqualTo("sqlite");
  }

  @Test
  void retainsExplicitDatabaseModesCaseInsensitively() {
    assertThat(ExchangeRuntimeFactory.databaseMode(" SQLITE ")).isEqualTo("sqlite");
    assertThat(ExchangeRuntimeFactory.databaseMode(" QuickShop ")).isEqualTo("quickshop");
  }

  @Test
  void explainsThatQuickShopH2RequiresLocalSQLite() {
    assertThat(ExchangeRuntimeFactory.unsupportedQuickShopDatabase("H2").getMessage())
        .contains("database.mode=quickshop")
        .contains("H2")
        .contains("database.mode: sqlite")
        .contains("addon data folder")
        .contains("exchange.sqlite");
  }

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

  @Test
  void confinesAuditExportsToTheAddonDataFolder() throws Exception {
    Path dataFolder = Files.createTempDirectory("quickshop-exchange-data-");

    assertThat(ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, "audit"))
        .isEqualTo(dataFolder.resolve("audit").toAbsolutePath());
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, "../outside"));
    Path absoluteOutside = dataFolder.resolveSibling("outside-audit").toAbsolutePath();
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.requireAuditDirectory(dataFolder, absoluteOutside.toString()));
  }

  @Test
  void maintenanceFlushRunsOnlyInsideTheWriterFence() {
    AtomicBoolean guarded = new AtomicBoolean();
    SingleWriterGuard writer = new SingleWriterGuard() {
      @Override public void acquire() {}
      @Override public boolean held() { return true; }
      @Override public boolean runWhileHeld(GuardedWork work) throws Exception {
        guarded.set(true);
        try {
          work.run();
          return true;
        } finally {
          guarded.set(false);
        }
      }
      @Override public void close() {}
    };
    AtomicBoolean flushed = new AtomicBoolean();

    ExchangeRuntimeFactory.runWhileOwned(writer, () -> {
      assertThat(guarded).isTrue();
      flushed.set(true);
    });

    assertThat(flushed).isTrue();
  }

  @Test
  void finalFlushFailsWhenWriterOwnershipIsUnavailable() {
    SingleWriterGuard writer = new SingleWriterGuard() {
      @Override public void acquire() {}
      @Override public boolean held() { return false; }
      @Override public boolean runWhileHeld(GuardedWork work) { return false; }
      @Override public void close() {}
    };

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        ExchangeRuntimeFactory.runWhileOwnedOrThrow(writer, () -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writer lock");
  }

  @Test
  void lockLossCallbackIsAWriteFreeLocalFence() throws Exception {
    AtomicBoolean completed = new AtomicBoolean();

    ExchangeRuntime.CheckedRunnable lockLossFence = ExchangeRuntimeFactory.lockLossFence();
    lockLossFence.run();
    completed.set(true);

    assertThat(completed).isTrue();
  }

  @Test
  void startupRollbackClosesResourcesInReverseOrderAndSuppressesLaterFailures() {
    List<String> closes = new ArrayList<>();
    Exception startupFailure = new Exception("startup failed");
    AutoCloseable first = () -> closes.add("first");
    AutoCloseable second = () -> {
      closes.add("second");
      throw new IllegalStateException("second close failed");
    };
    AutoCloseable third = () -> closes.add("third");

    ExchangeRuntimeFactory.closeOnStartupFailure(
        startupFailure, List.of(first, second, third));

    assertThat(closes).containsExactly("third", "second", "first");
    assertThat(startupFailure.getSuppressed())
        .extracting(Throwable::getMessage)
        .containsExactly("second close failed");
  }

  @Test
  void requiresAPositiveDisplayRefreshIntervalAndNonNegativeLimits() {
    assertThat(ExchangeRuntimeFactory.displayRefreshSeconds(5L)).isEqualTo(5L);
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.displayRefreshSeconds(0L));
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.displayRefreshSeconds(-1L));
    assertThat(ExchangeRuntimeFactory.displayLimit(0, "displays.max-signs")).isZero();
    assertThatIllegalArgumentException().isThrownBy(() ->
        ExchangeRuntimeFactory.displayLimit(-1, "displays.max-signs"));
  }

  @Test
  void publicMarketWebConfigUsesSafeDefaults() {
    org.bukkit.configuration.file.YamlConfiguration config =
        new org.bukkit.configuration.file.YamlConfiguration();

    PublicMarketWebConfig web = ExchangeRuntimeFactory.publicMarketWebConfig(config);

    assertThat(web).isEqualTo(PublicMarketWebConfig.defaults());
  }

  @Test
  void publicMarketWebConfigMapsExplicitResourceBounds() {
    org.bukkit.configuration.file.YamlConfiguration config =
        new org.bukkit.configuration.file.YamlConfiguration();
    config.set("web-api.enabled", true);
    config.set("web-api.bind-address", "127.0.0.1");
    config.set("web-api.port", 9876);
    config.set("web-api.cache-seconds", 7L);
    config.set("web-api.threads", 3);
    config.set("web-api.maximum-concurrent-requests", 24);

    PublicMarketWebConfig web = ExchangeRuntimeFactory.publicMarketWebConfig(config);

    assertThat(web.enabled()).isTrue();
    assertThat(web.bindAddress()).isEqualTo("127.0.0.1");
    assertThat(web.port()).isEqualTo(9876);
    assertThat(web.cacheDuration()).isEqualTo(java.time.Duration.ofSeconds(7));
    assertThat(web.threads()).isEqualTo(3);
    assertThat(web.maximumConcurrentRequests()).isEqualTo(24);
  }

  @Test
  void chartOptionsDefaultToAllProfessionalFeaturesEnabled() {
    MarketChartOptions options = MarketChartOptions.defaults();

    assertThat(options.professionalLayout()).isTrue();
    assertThat(options.includeLiveCandle()).isTrue();
    assertThat(options.showVolume()).isTrue();
    assertThat(options.showLatestPriceLine()).isTrue();
  }

  @Test
  void parsesAutomaticAndFixedChartIntervals() {
    assertThat(ExchangeRuntimeFactory.chartInterval(null)).isNull();
    assertThat(ExchangeRuntimeFactory.chartInterval(" auto ")).isNull();
    assertThat(ExchangeRuntimeFactory.chartInterval("15m"))
        .isEqualTo(com.ghostchu.quickshop.addon.exchange.display.MarketChartInterval.FIFTEEN_MINUTES);
  }

  @Test
  void mapsConfiguredAccountRiskLimitsIntoTheProductionService() {
    MarketDefinition.RiskRules rules = new MarketDefinition.RiskRules(
        new BigDecimal("0.001"), new BigDecimal("0.002"), new BigDecimal("0.20"),
        new BigDecimal("0.05"), new BigDecimal("0.20"), new BigDecimal("0.10"), 120L,
        new BigDecimal("0.20"), 600L, 321L, new BigDecimal("456.78"), 9, 3, 17);

    var limits = ExchangeRuntimeFactory.accountLimits(rules);

    assertThat(limits.maximumHolding()).isEqualTo(321L);
    assertThat(limits.maximumFrozenCurrency()).isEqualByComparingTo("456.78");
    assertThat(limits.maximumOpenOrders()).isEqualTo(9);
    assertThat(limits.operationsPerSecond()).isEqualTo(3);
    assertThat(limits.operationsPerMinute()).isEqualTo(17);
  }
}
