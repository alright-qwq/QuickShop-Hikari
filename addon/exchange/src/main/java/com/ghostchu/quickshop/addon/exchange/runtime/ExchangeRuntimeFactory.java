package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceMaintenance;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.display.BukkitDisplayTargets;
import com.ghostchu.quickshop.addon.exchange.display.ExchangeMarketDisplayDataSource;
import com.ghostchu.quickshop.addon.exchange.display.FoliaDisplayScheduler;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartCache;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartOptions;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartInterval;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartRenderer;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartSeriesBuilder;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayAdministration;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayListener;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayRegistry;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayService;
import com.ghostchu.quickshop.addon.exchange.display.MarketSignFormatter;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.persistence.TransactionFence;
import com.ghostchu.quickshop.addon.exchange.platform.FoliaInventoryGateway;
import com.ghostchu.quickshop.addon.exchange.platform.ContainerShopPolicyListener;
import com.ghostchu.quickshop.addon.exchange.platform.QuickShopEconomyGateway;
import com.ghostchu.quickshop.addon.exchange.platform.TransferLoginListener;
import com.ghostchu.quickshop.addon.exchange.platform.TransferMarkerListener;
import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.operations.TransferReviewCoordinator;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeActionService;
import com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.PlayerOperationSerialiser;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeViewService;
import com.ghostchu.quickshop.addon.exchange.web.PublicMarketCatalog;
import com.ghostchu.quickshop.addon.exchange.web.PublicMarketWebConfig;
import com.ghostchu.quickshop.addon.exchange.web.PublicMarketWebServer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Production composition root for the exchange's recoverable single-writer runtime. */
public final class ExchangeRuntimeFactory {
  private final JavaPlugin addon;
  private final QuickShop quickShop;

  public ExchangeRuntimeFactory(JavaPlugin addon, QuickShop quickShop) {
    this.addon = java.util.Objects.requireNonNull(addon, "addon");
    this.quickShop = java.util.Objects.requireNonNull(quickShop, "quickShop");
  }

  public ExchangeRuntime create() throws Exception {
    Database database = database();
    List<AutoCloseable> startupResources = new ArrayList<>();
    startupResources.add(database.writer());
    database.writer().acquire();
    try {
      TableNames tables = new TableNames(quickShop.getDbPrefix());
      File marketsFile = new File(addon.getDataFolder(), "markets.yml");
      File configFile = new File(addon.getDataFolder(), "config.yml");
      MarketRegistry configured = MarketRegistry.load(configFile, marketsFile);
      boolean migrated = database.writer().runWhileHeld(
          () -> new MigrationRunner(database.connections(), database.dialect(), tables).migrate());
      if (!migrated) {
        throw new IllegalStateException("exchange writer lock was lost during database migration");
      }
      TransactionFence transactionFence = database.writer().activateTransactionFence(tables);
      JdbcExchangeRepository repository = new JdbcExchangeRepository(
          database.connections(), database.dialect(), tables, transactionFence);
      boolean startupOwned = database.writer().runWhileHeld(() ->
          registerMarkets(database.connections(), tables, configured, transactionFence));
      if (!startupOwned) {
        throw new IllegalStateException("exchange writer lock was lost during database bootstrap");
      }
      MarketRegistry registry = MarketRegistry.load(configFile, marketsFile, repository);

    MarketDataService marketData = new MarketDataService(new CandleAggregator(), repository);
    Map<String, PersistentOrderService> markets = new java.util.LinkedHashMap<>();
    for (String marketId : registry.marketIds()) {
      MarketDefinition definition = registry.require(marketId);
      MarketRules rules = rules(definition);
      RiskLimits limits = limits(definition);
      markets.put(marketId, new PersistentOrderService(
          repository, rules, limits, RecoveryHandler.NO_OP,
          accountLimits(definition.risk()), marketData,
          definition.structural().discoveryQuantity(),
          definition.risk().trustedPricePolicy()));
    }

    PlayerOperationSerialiser playerOperations = new PlayerOperationSerialiser();
    startupResources.add(playerOperations);
    NamespacedKey marker = new NamespacedKey(addon, "exchange-transfer");
    FoliaInventoryGateway inventory = new FoliaInventoryGateway(quickShop, marker);
    MoneyTransferService moneyTransfers = new MoneyTransferService(repository, repository,
        new QuickShopEconomyGateway(quickShop, economyWorld()), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ItemTransferService itemTransfers = new ItemTransferService(repository, repository, inventory,
        marketId -> itemTemplate(registry.require(marketId)), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    ExchangeActionService actions = new ExchangeActionService(markets, moneyTransfers, itemTransfers);
    DrainingExecutor recoveryExecutor = new DrainingExecutor(
        "qs-exchange-recovery-", Duration.ofSeconds(30));
    startupResources.add(recoveryExecutor);
    DrainingExecutor recoveryFenceExecutor = new DrainingExecutor(
        "qs-exchange-recovery-fence-", Duration.ofSeconds(30));
    startupResources.add(recoveryFenceExecutor);
    DrainingExecutor reviewFenceExecutor = new DrainingExecutor(
        "qs-exchange-review-fence-", Duration.ofSeconds(30));
    startupResources.add(reviewFenceExecutor);
    Bukkit.getPluginManager().registerEvents(new ContainerShopPolicyListener(registry), addon);
    Bukkit.getPluginManager().registerEvents(new TransferMarkerListener(marker), addon);

    AutoCloseable dispatcher = () -> {};
    ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("qs-exchange-maintenance-", 0).factory());
    startupResources.add(() -> maintenance.shutdownNow());
    ExecutorService viewExecutor = Executors.newFixedThreadPool(2,
        Thread.ofPlatform().daemon(true).name("qs-exchange-view-", 0).factory());
    startupResources.add(() -> viewExecutor.shutdownNow());
    Map<String, ExchangeViewService.MarketView> marketViews = new java.util.LinkedHashMap<>();
    Map<String, com.ghostchu.quickshop.addon.exchange.ui.TransferTarget> transferTargets =
        new java.util.LinkedHashMap<>();
    for (Map.Entry<String, PersistentOrderService> entry : markets.entrySet()) {
      MarketDefinition definition = registry.require(entry.getKey());
      marketViews.put(entry.getKey(), new ExchangeViewService.MarketView(
          entry.getKey(), definition.displayName(), entry.getValue()));
      String currencyId = definition.structural().currencyId();
      transferTargets.putIfAbsent("currency:" + currencyId,
          com.ghostchu.quickshop.addon.exchange.ui.TransferTarget.currency(currencyId));
      transferTargets.put("item:" + entry.getKey(),
          com.ghostchu.quickshop.addon.exchange.ui.TransferTarget.item(
              entry.getKey(), definition.displayName()));
    }
    ExchangeViewService views = new ExchangeViewService(marketViews, marketData, viewExecutor,
        repository, java.util.List.copyOf(transferTargets.values()));
    MarketChartOptions chartOptions = chartOptions(addon.getConfig());
    Map<String, ExchangeMarketDisplayDataSource.MarketAccess> displayMarkets =
        new java.util.LinkedHashMap<>();
    for (Map.Entry<String, PersistentOrderService> entry : markets.entrySet()) {
      MarketDefinition definition = registry.require(entry.getKey());
      displayMarkets.put(entry.getKey(), new ExchangeMarketDisplayDataSource.MarketAccess(
          definition.displayName(), () -> entry.getValue().marketQuote(marketData),
          (fromInclusive, toExclusive) -> repository.loadCandles(
              entry.getKey(), fromInclusive, toExclusive),
          (fromInclusive, toExclusive) -> chartOptions.includeLiveCandle()
              ? marketData.liveCandles(entry.getKey(), fromInclusive, toExclusive)
              : List.of(),
          (fromInclusive, toExclusive) -> repository.loadTrustedPricePoints(
              entry.getKey(), fromInclusive, toExclusive),
          entry.getValue()::trustedPriceState));
    }
    ExchangeMarketDisplayDataSource displayDataSource =
        new ExchangeMarketDisplayDataSource(displayMarkets, viewExecutor);
    java.util.concurrent.atomic.AtomicReference<ExchangeRuntime> runtimeReference =
        new java.util.concurrent.atomic.AtomicReference<>();
    Map<String, String> publicDisplayNames = new java.util.LinkedHashMap<>();
    for (String marketId : registry.marketIds()) {
      publicDisplayNames.put(marketId, registry.require(marketId).displayName());
    }
    PublicMarketWebServer publicWeb = new PublicMarketWebServer(
        publicMarketWebConfig(addon.getConfig()),
        new PublicMarketCatalog(publicDisplayNames, displayDataSource),
        Clock.systemUTC(),
        () -> {
          ExchangeRuntime activeRuntime = runtimeReference.get();
          return activeRuntime != null && activeRuntime.acceptingWrites();
        });
    publicWeb.start();
    startupResources.add(publicWeb);
    MarketDisplayRegistry displayRegistry = MarketDisplayRegistry.load(
        addon.getDataFolder().toPath().resolve("displays.yml"));
    MarketDisplayService displayService = new MarketDisplayService(
        displayDataSource,
        new MarketChartSeriesBuilder(chartOptions.fixedInterval()),
        new MarketChartRenderer(chartOptions), new MarketChartCache(256),
        new MarketSignFormatter(), new FoliaDisplayScheduler(), Clock.systemUTC());
    startupResources.add(displayService);
    DrainingExecutor displayPersistence = new DrainingExecutor(
        "qs-exchange-display-persistence-", Duration.ofSeconds(30));
    startupResources.add(displayPersistence);
    MarketDisplayAdministration displayAdministration = new MarketDisplayAdministration(
        displayRegistry, marketId -> registry.marketIds().contains(marketId),
        new BukkitDisplayTargets(), new MarketDisplayAdministration.Refresher() {
          @Override
          public java.util.concurrent.CompletableFuture<Void> refresh(
              com.ghostchu.quickshop.addon.exchange.display.MapWallBinding binding) {
            return displayService.refresh(binding);
          }

          @Override
          public java.util.concurrent.CompletableFuture<Void> refresh(
              com.ghostchu.quickshop.addon.exchange.display.MarketSignBinding binding) {
            return displayService.refresh(binding);
          }
        }, displayRegistry::save, UUID::randomUUID,
        displayLimit(addon.getConfig().getInt("displays.max-map-walls", 128),
            "displays.max-map-walls"),
        displayLimit(addon.getConfig().getInt("displays.max-signs", 256),
            "displays.max-signs"), displayPersistence);
    displayAdministration.failureReporter((cause, context) ->
        addon.getLogger().log(java.util.logging.Level.SEVERE,
            "Exchange display " + context + " failed", cause));
    Bukkit.getPluginManager().registerEvents(
        new MarketDisplayListener(displayRegistry, displayService), addon);
    if (addon.getConfig().getBoolean("displays.enabled", true)) {
      long refreshSeconds = displayRefreshSeconds(
          addon.getConfig().getLong("displays.refresh-seconds", 5L));
      maintenance.scheduleWithFixedDelay(
          () -> refreshDisplays(displayRegistry, displayService, addon),
          refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }
    java.nio.file.Path auditDirectory = requireAuditDirectory(
        addon.getDataFolder().toPath(),
        addon.getConfig().getString("operations.audit-export-directory", "audit"));
    AdminExchangeService administration = new AdminExchangeService(
        markets, repository,
        (com.ghostchu.quickshop.addon.exchange.config.MarketDefinition definition) ->
            new PersistentOrderService(
                repository, rules(definition), limits(definition),
                com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler.NO_OP,
                accountLimits(definition.risk()), marketData,
                definition.structural().discoveryQuantity(),
                definition.risk().trustedPricePolicy()),
        new com.ghostchu.quickshop.addon.exchange.operations.AuditExporter(),
        auditDirectory);
    TransferReviewCoordinator transferReviews = new TransferReviewCoordinator(
        administration, inventory, work -> {
          ExchangeRuntime activeRuntime = runtimeReference.get();
          if (activeRuntime == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("exchange runtime is unavailable"));
          }
          return activeRuntime.callAsyncWhileWriting(
                  () -> java.util.concurrent.CompletableFuture.completedFuture(work.get()),
                  reviewFenceExecutor)
              .thenCompose(result -> result
                  .<java.util.concurrent.CompletableFuture<
                      com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord>>map(
                          java.util.concurrent.CompletableFuture::completedFuture)
                  .orElseGet(() -> java.util.concurrent.CompletableFuture.failedFuture(
                      new IllegalStateException("exchange writer is unavailable"))));
        });
    TransferRecoveryService transfers = new TransferRecoveryService(
        repository, repository, inventory, recoveryExecutor, transferReviews::recoverClaimed);
    Runnable resumeHalted = () -> resumeExpiredHalts(repository, registry.marketIds(), database.writer());
    maintenance.scheduleWithFixedDelay(resumeHalted, 1L, 1L, TimeUnit.MINUTES);
    maintenance.scheduleWithFixedDelay(() -> flushWhileOwned(
        database.writer(), marketData, Instant.now()), 1L, 1L, TimeUnit.MINUTES);
    maintenance.scheduleWithFixedDelay(marketData::publishPlayerUpdates,
        1L, 1L, TimeUnit.SECONDS);

      ExchangeRuntime runtime = new ExchangeRuntime(database.writer(),
          () -> recoverMarkets(markets), transfers::recoverAllMoneyTransfers, dispatcher,
          lockLossFence(),
          () -> {
            maintenance.shutdownNow();
            reviewFenceExecutor.close();
            recoveryFenceExecutor.close();
            recoveryExecutor.close();
            playerOperations.close();
            runWhileOwnedOrThrow(database.writer(), () -> marketData.flush(Instant.now()));
          }, views, administration, actions, transferReviews, displayService,
          displayAdministration, () -> {
            maintenance.shutdownNow();
            displayAdministration.close();
            displayPersistence.close();
            displayService.close();
            displayRegistry.save();
            viewExecutor.shutdown();
            if (!viewExecutor.awaitTermination(30L, TimeUnit.SECONDS)) {
              throw new IllegalStateException("timed out draining exchange view executor");
            }
          }, () -> runTrustedPriceMaintenance(
              repository, registry, markets, Instant.now()), publicWeb::close);
      runtimeReference.set(runtime);
      maintenance.scheduleWithFixedDelay(() -> runTrustedPriceMaintenance(runtime),
          1L, 1L, TimeUnit.MINUTES);
      Bukkit.getPluginManager().registerEvents(new TransferLoginListener(accountId ->
          runtime.callAsyncWhileWriting(() -> transfers.recoverPlayer(accountId),
              recoveryFenceExecutor),
          (accountId, failure) -> addon.getLogger().log(
              java.util.logging.Level.SEVERE,
              "Exchange login recovery failed for account " + accountId,
              failure)), addon);
      startupResources.clear();
      return runtime;
    } catch (Exception failure) {
      closeOnStartupFailure(failure, startupResources);
      throw failure;
    }
  }

  static long displayRefreshSeconds(long configured) {
    if (configured <= 0L) {
      throw new IllegalArgumentException("displays.refresh-seconds must be positive");
    }
    return configured;
  }

  static int displayLimit(int configured, String path) {
    if (configured < 0) {
      throw new IllegalArgumentException(path + " must not be negative");
    }
    return configured;
  }

  static PublicMarketWebConfig publicMarketWebConfig(FileConfiguration config) {
    Objects.requireNonNull(config, "config");
    PublicMarketWebConfig defaults = PublicMarketWebConfig.defaults();
    return new PublicMarketWebConfig(
        config.getBoolean("web-api.enabled", defaults.enabled()),
        config.getString("web-api.bind-address", defaults.bindAddress()),
        config.getInt("web-api.port", defaults.port()),
        Duration.ofSeconds(config.getLong(
            "web-api.cache-seconds", defaults.cacheDuration().toSeconds())),
        config.getInt("web-api.threads", defaults.threads()),
        config.getInt("web-api.maximum-concurrent-requests",
            defaults.maximumConcurrentRequests()));
  }

  static MarketChartOptions chartOptions(FileConfiguration config) {
    Objects.requireNonNull(config, "config");
    return new MarketChartOptions(
        config.getBoolean("displays.chart.professional-layout", true),
        config.getBoolean("displays.chart.include-live-candle", true),
        config.getBoolean("displays.chart.show-volume", true),
        config.getBoolean("displays.chart.show-latest-price-line", true),
        config.getBoolean("displays.chart.show-trusted-price-line", true),
        config.getBoolean("displays.chart.show-gap-markers", true),
        chartInterval(config.getString("displays.chart.interval", "auto")));
  }

  static MarketChartInterval chartInterval(String configured) {
    return MarketChartInterval.parse(configured);
  }

  static void refreshDisplays(MarketDisplayRegistry registry, MarketDisplayService displays,
                              JavaPlugin addon) {
    registry.mapWalls().forEach(binding -> refreshDisplay(displays.refresh(binding), binding.bindingId(), addon));
    registry.signs().forEach(binding -> refreshDisplay(displays.refresh(binding), binding.bindingId(), addon));
  }

  private static void refreshDisplay(java.util.concurrent.CompletableFuture<Void> refresh,
                                     UUID bindingId, JavaPlugin addon) {
    refresh.exceptionally(failure -> {
      addon.getLogger().log(java.util.logging.Level.WARNING,
          "Failed to refresh exchange market display " + bindingId, failure);
      return null;
    });
  }

  static void closeOnStartupFailure(Throwable failure, List<? extends AutoCloseable> resources) {
    Objects.requireNonNull(failure, "failure");
    Objects.requireNonNull(resources, "resources");
    for (int index = resources.size() - 1; index >= 0; index--) {
      try {
        resources.get(index).close();
      } catch (Throwable closeFailure) {
        if (closeFailure != failure) {
          failure.addSuppressed(closeFailure);
        }
      }
    }
  }

  static void flushWhileOwned(SingleWriterGuard writer, MarketDataService marketData, Instant at) {
    runWhileOwned(writer, () -> marketData.flush(at));
  }

  private static void runTrustedPriceMaintenance(ExchangeRuntime runtime) {
    try {
      runtime.runTrustedPriceMaintenance();
    } catch (Exception ignored) {
      // The next minute retries from the last committed state.
    }
  }

  /** Applies one durable, bounded no-trade sweep and publishes each market only after commit. */
  static void runTrustedPriceMaintenance(
      JdbcExchangeRepository repository, MarketRegistry registry,
      Map<String, PersistentOrderService> markets, Instant now) throws SQLException {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(now, "now");
    TrustedPriceMaintenance evaluator = new TrustedPriceMaintenance();
    for (String marketId : registry.marketIds()) {
      PersistentOrderService market = markets.get(marketId);
      if (market == null) {
        continue;
      }
      MarketDefinition definition = registry.require(marketId);
      TrustedPricePolicy policy = definition.risk().trustedPricePolicy();
      long policyVersion = registry.versions(marketId).riskVersion();
      TrustedPriceState committed = repository.inTransaction(tx -> {
        var snapshot = tx.trustedMarketSnapshot(marketId,
            now.minus(policy.budgetWindow()), now.minus(policy.confidenceWindow()));
        TrustedPriceState current = snapshot.state();
        TrustedPriceState versioned = current.policyVersion() == policyVersion ? current
            : new TrustedPriceState(current.marketId(), current.trustedPrice(),
                current.guidancePrice(), current.lastEvaluatedAt(), current.liquidityTier(),
                policyVersion, current.lastMatchSequence(), current.stateVersion());
        TrustedPriceMaintenance.Result result = evaluator.evaluate(
            versioned, policy, now, definition.structural().priceScale());
        TrustedPriceState next = result.state();
        if (result.adjustment() != null) {
          tx.insertTrustedAdjustment(result.adjustment());
          tx.updateTrustedPriceState(next, current.stateVersion());
          return next;
        }
        if (versioned != current) {
          next = new TrustedPriceState(versioned.marketId(), versioned.trustedPrice(),
              versioned.guidancePrice(), versioned.lastEvaluatedAt(), versioned.liquidityTier(),
              versioned.policyVersion(), versioned.lastMatchSequence(),
              Math.addExact(versioned.stateVersion(), 1));
          tx.updateTrustedPriceState(next, current.stateVersion());
          return next;
        }
        return current;
      });
      // Publication is deliberately after the transaction returns/commits. Publishing an
      // unchanged state makes a prior post-commit publication failure self-heal next minute.
      market.publishCommittedTrustedState(committed);
    }
  }

  static void runWhileOwned(SingleWriterGuard writer, ExchangeRuntime.CheckedRunnable work) {
    try {
      writer.runWhileHeld(work::run);
    } catch (Exception ignored) {
      // The next owned maintenance tick or startup recovery retries durable publication.
    }
  }

  static void runWhileOwnedOrThrow(SingleWriterGuard writer, ExchangeRuntime.CheckedRunnable work)
      throws Exception {
    if (!writer.runWhileHeld(work::run)) {
      throw new IllegalStateException("exchange writer lock is unavailable during final flush");
    }
  }

  static ExchangeRuntime.CheckedRunnable lockLossFence() {
    return () -> {
      // Ownership is already untrusted. Only the runtime's local accepting-writes flag may change;
      // persistent recovery state is established by the next process after it legitimately acquires.
    };
  }

  static Path requireAuditDirectory(Path dataFolder, String configured) {
    if (dataFolder == null || configured == null || configured.isBlank()) {
      throw new IllegalArgumentException("audit export directory is required");
    }
    Path root = dataFolder.toAbsolutePath().normalize();
    Path relative = Path.of(configured);
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException("audit export directory must be relative to addon data");
    }
    Path candidate = root.resolve(relative).normalize();
    if (!candidate.startsWith(root) || candidate.equals(root)) {
      throw new IllegalArgumentException("audit export directory must stay inside addon data");
    }
    return candidate;
  }

  static Path requireLocalSqlitePath(Path dataFolder, String jdbcUrl) {
    if (dataFolder == null || jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) {
      throw new IllegalArgumentException("a local SQLite JDBC URL is required");
    }
    String rawPath = jdbcUrl.substring("jdbc:sqlite:".length());
    if (rawPath.isBlank() || rawPath.startsWith("file:") || ":memory:".equals(rawPath)) {
      throw new IllegalArgumentException("SQLite must use a local database file");
    }
    Path root = dataFolder.toAbsolutePath().normalize();
    Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("SQLite database must be inside the addon data folder");
    }
    return candidate;
  }

  static String databaseMode(String configured) {
    if (configured == null || configured.isBlank()) {
      return "sqlite";
    }
    return configured.trim().toLowerCase(java.util.Locale.ROOT);
  }

  static IllegalStateException unsupportedQuickShopDatabase(String productName) {
    String detected = productName == null || productName.isBlank() ? "unknown" : productName;
    return new IllegalStateException(
        "database.mode=quickshop can reuse only a MySQL QuickShop database; detected "
            + detected + ". Set database.mode: sqlite to use the safe local exchange.sqlite "
            + "database inside the Exchange addon data folder instead");
  }

  private Database database() throws Exception {
    FileConfiguration config = addon.getConfig();
    String mode = databaseMode(config.getString("database.mode"));
    if ("sqlite".equals(mode)) {
      Path folder = addon.getDataFolder().toPath();
      Files.createDirectories(folder);
      String configured = config.getString("database.sqlite-jdbc-url",
          "jdbc:sqlite:" + folder.resolve("exchange.sqlite").toAbsolutePath());
      Path databaseFile = requireLocalSqlitePath(folder, configured);
      ConnectionProvider connections = new SqliteConnectionProvider(
          () -> java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile));
      return new Database(connections, SqlDialect.SQLITE, new LocalSingleWriterGuard(databaseFile));
    }
    if (!"quickshop".equals(mode)) {
      throw new IllegalArgumentException("database.mode must be quickshop or sqlite");
    }
    ConnectionProvider connections = () -> quickShop.getSqlManager().getConnection();
    try (Connection connection = connections.open()) {
      String productName = connection.getMetaData().getDatabaseProductName();
      if (!"MySQL".equalsIgnoreCase(productName)) {
        throw unsupportedQuickShopDatabase(productName);
      }
    }
    return new Database(connections, SqlDialect.MYSQL,
        new MySqlSingleWriterGuard(connections::open, quickShop.getDbPrefix()));
  }

  private void registerMarkets(
      ConnectionProvider connections, TableNames tables, MarketRegistry registry,
      TransactionFence transactionFence) throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
        transactionFence.acquire(connection);
        for (String marketId : registry.marketIds()) {
          MarketDefinition definition = registry.require(marketId);
          if (marketExists(connection, tables, marketId)) {
            continue;
          }
          insertMarket(connection, tables, definition);
        }
        connection.commit();
      } catch (SQLException | RuntimeException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static boolean marketExists(Connection connection, TableNames tables, String marketId)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT market_id FROM " + tables.markets() + " WHERE market_id=?")) {
      query.setString(1, marketId);
      try (ResultSet result = query.executeQuery()) {
        return result.next();
      }
    }
  }

  private static void insertMarket(Connection connection, TableNames tables, MarketDefinition definition)
      throws SQLException {
    MarketRules rules = rules(definition);
    try (PreparedStatement market = connection.prepareStatement(
        "INSERT INTO " + tables.markets()
            + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
            + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)");
         PreparedStatement state = connection.prepareStatement(
             "INSERT INTO " + tables.marketState()
                 + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                 + "last_price,halted_until,discovery_quantity,circuit_breaker_level,version)"
                 + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      market.setString(1, definition.marketId());
      market.setString(2, definition.structural().currencyId());
      market.setString(3, definition.item().fingerprint() == null
          ? definition.item().material() : definition.item().fingerprint());
      market.setString(4, Optional.ofNullable(definition.item().encodedTemplate()).orElse(""));
      market.setString(5, "{}");
      market.setString(6, "{\"makerFeeRate\":\"" + rules.makerFeeRate().toPlainString()
          + "\",\"takerFeeRate\":\"" + rules.takerFeeRate().toPlainString()
          + "\",\"currencyScale\":" + definition.structural().currencyScale() + "}");
      market.setString(7, "{}");
      market.setLong(8, 1L);
      market.setLong(9, 1L);
      market.setLong(10, Instant.now().toEpochMilli());
      market.executeUpdate();

      state.setString(1, definition.marketId());
      state.setString(2, definition.enabled() ? MarketStatus.OPEN.name() : MarketStatus.CLOSED.name());
      state.setLong(3, 0L);
      state.setLong(4, 0L);
      state.setString(5, rules.basePrice().toPlainString());
      state.setNull(6, Types.DECIMAL);
      state.setNull(7, Types.BIGINT);
      state.setLong(8, 0L);
      state.setInt(9, 0);
      state.setLong(10, 0L);
      state.executeUpdate();
    }
  }

  private static void recoverMarkets(Map<String, PersistentOrderService> markets)
      throws SQLException {
    for (PersistentOrderService market : markets.values()) {
      market.recoverFromDatabase();
    }
  }

  private static void resumeExpiredHalts(JdbcExchangeRepository repository,
                                          Collection<String> marketIds,
                                          SingleWriterGuard writer) {
    try {
      writer.runWhileHeld(() -> repository.inTransaction(tx -> {
        Instant now = Instant.now();
        for (String marketId : marketIds) {
          MarketState state = tx.marketState(marketId);
          if (state.status() == MarketStatus.HALTED && state.haltedUntil() != null
              && !now.isBefore(state.haltedUntil())) {
            tx.updateMarketState(new MarketState(marketId, MarketStatus.OPEN,
                state.prioritySequence(), state.matchSequence(), state.referencePrice(),
                state.lastPrice(), null, state.discoveryQuantity(), state.circuitBreakerLevel(),
                state.version() + 1), state.version());
          }
        }
        return null;
      }));
    } catch (Exception ignored) {
      // A later maintenance tick retries; CAS versioning prevents stale automatic reopen.
    }
  }

  private ItemStack itemTemplate(MarketDefinition definition) {
    if (definition.item().encodedTemplate() != null && !definition.item().encodedTemplate().isBlank()) {
      ItemStack decoded = quickShop.platform().decodeStack(definition.item().encodedTemplate());
      if (decoded == null) {
        throw new IllegalStateException("configured market template cannot be decoded");
      }
      return decoded;
    }
    Material material = Material.matchMaterial(definition.item().material());
    if (material == null || material.isAir()) {
      throw new IllegalStateException("configured market material is invalid");
    }
    return new ItemStack(material);
  }

  private String economyWorld() {
    String world = addon.getConfig().getString("economy.world");
    if (world == null || world.isBlank()) {
      throw new IllegalArgumentException("economy.world is required");
    }
    return world;
  }

  static MarketRules rules(MarketDefinition definition) {
    MarketDefinition.StructuralRules structural = definition.structural();
    MarketDefinition.RiskRules risk = definition.risk();
    return new MarketRules(definition.marketId(), structural.currencyId(), structural.basePrice(),
        structural.minPrice(), structural.maxPrice(), structural.tickSize(),
        structural.minQuantity(), structural.maxQuantity(), structural.priceScale(),
        risk.makerFeeRate(), risk.takerFeeRate());
  }

  static RiskLimits limits(MarketDefinition definition) {
    MarketDefinition.RiskRules risk = definition.risk();
    return new RiskLimits(risk.priceCageRatio(), risk.defaultMarketSlippage(),
        risk.maximumMarketSlippage(), risk.levelOneMove(),
        Duration.ofSeconds(risk.levelOneHaltSeconds()), risk.levelTwoMove(),
        Duration.ofSeconds(risk.levelTwoHaltSeconds()));
  }

  static AccountOrderLimits accountLimits(MarketDefinition.RiskRules risk) {
    java.util.Objects.requireNonNull(risk, "risk");
    return new AccountOrderLimits(risk.maxAccountHolding(), risk.maxFrozenCurrency(),
        risk.maxOpenOrders(), risk.operationsPerSecond(), risk.operationsPerMinute());
  }

  private record Database(ConnectionProvider connections, SqlDialect dialect, SingleWriterGuard writer) {}
}
