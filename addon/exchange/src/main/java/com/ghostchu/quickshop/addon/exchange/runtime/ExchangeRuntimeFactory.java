package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.service.CommandResult;
import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.core.service.RequestResultStore;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.platform.FoliaInventoryGateway;
import com.ghostchu.quickshop.addon.exchange.platform.QuickShopEconomyGateway;
import com.ghostchu.quickshop.addon.exchange.platform.TransferLoginListener;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler;
import com.ghostchu.quickshop.addon.exchange.transfer.ItemTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.MoneyTransferService;
import com.ghostchu.quickshop.addon.exchange.transfer.PlayerOperationSerialiser;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
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
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
    database.writer().acquire();
    try {
      TableNames tables = new TableNames(quickShop.getDbPrefix());
      new MigrationRunner(database.connections(), database.dialect(), tables).migrate();
      JdbcExchangeRepository repository = new JdbcExchangeRepository(
          database.connections(), database.dialect(), tables);

      File marketsFile = new File(addon.getDataFolder(), "markets.yml");
      File configFile = new File(addon.getDataFolder(), "config.yml");
      MarketRegistry configured = MarketRegistry.load(configFile, marketsFile);
      registerMarkets(database.connections(), tables, configured);
      MarketRegistry registry = MarketRegistry.load(configFile, marketsFile, repository);

    MarketDataService marketData = new MarketDataService(new CandleAggregator(), repository);
    Map<String, PersistentOrderService> markets = new java.util.LinkedHashMap<>();
    for (String marketId : registry.marketIds()) {
      MarketDefinition definition = registry.require(marketId);
      MarketRules rules = rules(definition);
      RiskLimits limits = limits(definition);
      markets.put(marketId, new PersistentOrderService(
          repository, rules, limits, RecoveryHandler.NO_OP, marketData));
    }

    PlayerOperationSerialiser playerOperations = new PlayerOperationSerialiser();
    NamespacedKey marker = new NamespacedKey(addon, "exchange-transfer");
    FoliaInventoryGateway inventory = new FoliaInventoryGateway(quickShop, marker);
    new MoneyTransferService(repository, repository,
        new QuickShopEconomyGateway(quickShop, economyWorld()), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    new ItemTransferService(repository, repository, inventory,
        marketId -> itemTemplate(registry.require(marketId)), playerOperations,
        Clock.systemUTC(), UUID::randomUUID);
    TransferRecoveryService transfers = new TransferRecoveryService(
        repository, repository, inventory, Runnable::run);
    Bukkit.getPluginManager().registerEvents(new TransferLoginListener(transfers), addon);

    MarketDispatcher dispatcher = new MarketDispatcher(requestResults(), command ->
        new CommandResult(command.requestId(), "accepted"));
    ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("qs-exchange-maintenance-", 0).factory());
    Runnable resumeHalted = () -> resumeExpiredHalts(repository, registry.marketIds(), database.writer());
    maintenance.scheduleWithFixedDelay(resumeHalted, 1L, 1L, TimeUnit.MINUTES);

      return new ExchangeRuntime(database.writer(),
          () -> recoverMarkets(markets), transfers::recoverAllMoneyTransfers, dispatcher,
          () -> markAllRecovering(repository, registry.marketIds()),
          () -> {
            maintenance.shutdownNow();
            marketData.flush(Instant.now());
            playerOperations.close();
          });
    } catch (Exception failure) {
      database.writer().close();
      throw failure;
    }
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

  private Database database() throws Exception {
    FileConfiguration config = addon.getConfig();
    String mode = config.getString("database.mode", "quickshop");
    if ("sqlite".equalsIgnoreCase(mode)) {
      Path folder = addon.getDataFolder().toPath();
      Files.createDirectories(folder);
      String configured = config.getString("database.sqlite-jdbc-url",
          "jdbc:sqlite:" + folder.resolve("exchange.sqlite").toAbsolutePath());
      Path databaseFile = requireLocalSqlitePath(folder, configured);
      ConnectionProvider connections = new SqliteConnectionProvider(
          () -> java.sql.DriverManager.getConnection("jdbc:sqlite:" + databaseFile));
      return new Database(connections, SqlDialect.SQLITE, new LocalSingleWriterGuard(databaseFile));
    }
    if (!"quickshop".equalsIgnoreCase(mode)) {
      throw new IllegalArgumentException("database.mode must be quickshop or sqlite");
    }
    ConnectionProvider connections = () -> quickShop.getSqlManager().getConnection();
    try (Connection connection = connections.open()) {
      if (!"MySQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
        throw new IllegalStateException(
            "QuickShop Exchange requires MySQL for database.mode=quickshop; use local sqlite otherwise");
      }
    }
    return new Database(connections, SqlDialect.MYSQL,
        new MySqlSingleWriterGuard(connections::open, quickShop.getDbPrefix()));
  }

  private void registerMarkets(ConnectionProvider connections, TableNames tables, MarketRegistry registry)
      throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
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

  private static void markAllRecovering(JdbcExchangeRepository repository, Collection<String> marketIds)
      throws SQLException {
    repository.inTransaction(tx -> {
      for (String marketId : marketIds) {
        MarketState state = tx.marketState(marketId);
        if (state.status() != MarketStatus.RECOVERING) {
          tx.updateMarketState(new MarketState(marketId, MarketStatus.RECOVERING,
              state.prioritySequence(), state.matchSequence(), state.referencePrice(),
              state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
              state.circuitBreakerLevel(), state.version() + 1), state.version());
        }
      }
      return null;
    });
  }

  private static void resumeExpiredHalts(JdbcExchangeRepository repository,
                                          Collection<String> marketIds,
                                          SingleWriterGuard writer) {
    if (!writer.held()) {
      return;
    }
    try {
      repository.inTransaction(tx -> {
        if (!writer.held()) {
          return null;
        }
        Instant now = Instant.now();
        for (String marketId : marketIds) {
          MarketState state = tx.marketState(marketId);
          if (state.status() == MarketStatus.HALTED && state.haltedUntil() != null
              && !now.isBefore(state.haltedUntil())) {
            if (!writer.held()) {
              return null;
            }
            tx.updateMarketState(new MarketState(marketId, MarketStatus.OPEN,
                state.prioritySequence(), state.matchSequence(), state.referencePrice(),
                state.lastPrice(), null, state.discoveryQuantity(), state.circuitBreakerLevel(),
                state.version() + 1), state.version());
          }
        }
        return null;
      });
    } catch (SQLException | RuntimeException ignored) {
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

  private static MarketRules rules(MarketDefinition definition) {
    MarketDefinition.StructuralRules structural = definition.structural();
    MarketDefinition.RiskRules risk = definition.risk();
    return new MarketRules(definition.marketId(), structural.currencyId(), structural.basePrice(),
        structural.minPrice(), structural.maxPrice(), structural.tickSize(),
        structural.minQuantity(), structural.maxQuantity(), structural.priceScale(),
        risk.makerFeeRate(), risk.takerFeeRate());
  }

  private static RiskLimits limits(MarketDefinition definition) {
    MarketDefinition.RiskRules risk = definition.risk();
    return new RiskLimits(risk.priceCageRatio(), risk.defaultMarketSlippage(),
        risk.maximumMarketSlippage(), risk.levelOneMove(),
        Duration.ofSeconds(risk.levelOneHaltSeconds()), risk.levelTwoMove(),
        Duration.ofSeconds(risk.levelTwoHaltSeconds()));
  }

  private static RequestResultStore requestResults() {
    Map<RequestKey, CommandResult> results = new ConcurrentHashMap<>();
    return new RequestResultStore() {
      @Override
      public Optional<CommandResult> find(UUID accountId, UUID requestId) {
        return Optional.ofNullable(results.get(new RequestKey(accountId, requestId)));
      }

      @Override
      public CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result) {
        return results.computeIfAbsent(new RequestKey(accountId, requestId), ignored -> result);
      }
    };
  }

  private record Database(ConnectionProvider connections, SqlDialect dialect, SingleWriterGuard writer) {}

  private record RequestKey(UUID accountId, UUID requestId) {}
}
