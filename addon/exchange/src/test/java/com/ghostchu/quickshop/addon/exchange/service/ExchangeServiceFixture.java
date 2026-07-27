package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

final class ExchangeServiceFixture {
  private final ConnectionProvider connections;
  private final TableNames tables;
  private final JdbcExchangeRepository repository;
  private final PersistentOrderService service;
  private final MarketRules rules;

  private ExchangeServiceFixture(ConnectionProvider connections, TableNames tables,
                                 JdbcExchangeRepository repository,
                                 PersistentOrderService service, MarketRules rules) {
    this.connections = connections;
    this.tables = tables;
    this.repository = repository;
    this.service = service;
    this.rules = rules;
  }

  static ExchangeServiceFixture sqlite() throws Exception {
    return sqlite(RecoveryHandler.NO_OP);
  }

  static ExchangeServiceFixture sqlite(RecoveryHandler recovery) throws Exception {
    return sqlite(TestFixtures.rules(), recovery);
  }

  static ExchangeServiceFixture sqliteWithFees(String makerFee, String takerFee) throws Exception {
    MarketRules defaults = TestFixtures.rules();
    MarketRules rules = new MarketRules(defaults.marketId(), defaults.currencyId(),
        defaults.basePrice(), defaults.minPrice(), defaults.maxPrice(), defaults.tickSize(),
        defaults.minQuantity(), defaults.maxQuantity(), defaults.priceScale(),
        new BigDecimal(makerFee), new BigDecimal(takerFee));
    return sqlite(rules, RecoveryHandler.NO_OP);
  }

  private static ExchangeServiceFixture sqlite(MarketRules rules, RecoveryHandler recovery)
      throws Exception {
    Path database = Files.createTempFile("quickshop-exchange-service-", ".sqlite");
    database.toFile().deleteOnExit();
    ConnectionProvider connections = new SqliteConnectionProvider(
        () -> DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath()));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    seedMarket(connections, tables, rules);
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    PersistentOrderService service = new PersistentOrderService(
        repository, rules, com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        recovery);
    return new ExchangeServiceFixture(connections, tables, repository, service, rules);
  }

  PersistentOrderService service() {
    return service;
  }

  PersistentOrderService restartedService() {
    return new PersistentOrderService(repository, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(),
        RecoveryHandler.NO_OP);
  }

  PersistentOrderService serviceWithReportedCommitFailure(RecoveryHandler recovery) {
    AtomicBoolean failOnce = new AtomicBoolean(true);
    ExchangeRepository uncertainCommit = new ExchangeRepository() {
      @Override
      public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        T result = repository.inTransaction(work);
        if (failOnce.compareAndSet(true, false)) {
          throw new SQLException("reported failure after commit");
        }
        return result;
      }
    };
    return new PersistentOrderService(uncertainCommit, rules,
        com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits.defaults(), recovery);
  }

  UUID accountWithItems(long quantity) throws SQLException {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableItems(account, rules.marketId(), quantity);
      return null;
    });
    return account;
  }

  UUID accountWithCurrency(String amount) throws SQLException {
    UUID account = UUID.randomUUID();
    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, rules.currencyId(),
          new BigDecimal(amount));
      return null;
    });
    return account;
  }

  long tradeCount() throws SQLException {
    return rowCount(tables.trades());
  }

  long orderCount() throws SQLException {
    return rowCount(tables.orders());
  }

  private long rowCount(String table) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + table);
         ResultSet result = query.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  boolean ledgerIsBalanced() throws SQLException {
    Map<String, BigDecimal> totals = new HashMap<>();
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT asset_id,amount FROM " + tables.entries());
         ResultSet result = query.executeQuery()) {
      while (result.next()) {
        totals.merge(result.getString("asset_id"),
            new BigDecimal(result.getString("amount")), BigDecimal::add);
      }
    }
    return totals.values().stream().allMatch(total -> total.compareTo(BigDecimal.ZERO) == 0);
  }

  BigDecimal feeAccountBalance() throws SQLException {
    return repository.inTransaction(tx -> tx.currency(
        PersistentOrderService.FEE_ACCOUNT_ID, rules.currencyId()).available());
  }

  BigDecimal availableCurrency(UUID account) throws SQLException {
    return repository.inTransaction(
        tx -> tx.currency(account, rules.currencyId()).available());
  }

  BigDecimal frozenCurrency(UUID account) throws SQLException {
    return repository.inTransaction(
        tx -> tx.currency(account, rules.currencyId()).frozen());
  }

  long availableItems(UUID account) throws SQLException {
    return repository.inTransaction(tx -> tx.inventory(account, rules.marketId()).availableQuantity());
  }

  long frozenItems(UUID account) throws SQLException {
    return repository.inTransaction(tx -> tx.inventory(account, rules.marketId()).frozenQuantity());
  }

  void setMarketStatus(String status) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState() + " SET status=? WHERE market_id=?")) {
      update.setString(1, status);
      update.setString(2, rules.marketId());
      update.executeUpdate();
    }
  }

  long marketPrioritySequence() throws SQLException {
    return Long.parseLong(marketValue("priority_sequence"));
  }

  long marketVersion() throws SQLException {
    return Long.parseLong(marketValue("version"));
  }

  void resumeMarket() throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement update = connection.prepareStatement(
             "UPDATE " + tables.marketState()
                 + " SET status='OPEN',halted_until=NULL,version=version+1 WHERE market_id=?")) {
      update.setString(1, rules.marketId());
      update.executeUpdate();
    }
  }

  long highAlertCount() throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT COUNT(*) FROM " + tables.auditAlerts()
                 + " WHERE severity='HIGH' AND alert_type='CIRCUIT_BREAKER_LEVEL_2'");
         ResultSet result = query.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  void storeRequestResult(UUID account, UUID request, String operation, String payload)
      throws SQLException {
    repository.inTransaction(tx -> {
      tx.putRequestResult(new StoredRequestResult(account, request, operation, payload));
      return null;
    });
  }

  void failTradeInserts() throws SQLException {
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER fail_exchange_trade_insert BEFORE INSERT ON "
          + tables.trades() + " BEGIN SELECT RAISE(ABORT,'forced trade insert failure'); END");
    }
  }

  String marketStatus() throws SQLException {
    return marketValue("status");
  }

  BigDecimal marketReferencePrice() throws SQLException {
    return new BigDecimal(marketValue("reference_price"));
  }

  BigDecimal marketLastPrice() throws SQLException {
    return new BigDecimal(marketValue("last_price"));
  }

  Long marketHaltedUntil() throws SQLException {
    String value = marketValue("halted_until");
    return value == null ? null : Long.valueOf(value);
  }

  Set<String> journalAccountKinds() throws SQLException {
    Set<String> kinds = new HashSet<>();
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT account_code FROM " + tables.entries());
         ResultSet result = query.executeQuery()) {
      while (result.next()) {
        String code = result.getString(1);
        if (code.startsWith("liability:currency:")) {
          kinds.add(kinds.contains("buyer-currency") ? "seller-currency" : "buyer-currency");
        } else if (code.startsWith("liability:fee:")) {
          kinds.add("fee-currency");
        } else if (code.startsWith("custody:currency:")) {
          kinds.add("currency-custody");
        } else if (code.startsWith("liability:item:")) {
          kinds.add(kinds.contains("seller-item") ? "buyer-item" : "seller-item");
        } else if (code.startsWith("custody:item:")) {
          kinds.add("item-custody");
        }
      }
    }
    return Set.copyOf(kinds);
  }

  private String marketValue(String column) throws SQLException {
    try (Connection connection = connections.open();
         PreparedStatement query = connection.prepareStatement(
             "SELECT " + column + " FROM " + tables.marketState() + " WHERE market_id=?")) {
      query.setString(1, rules.marketId());
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("market state missing");
        }
        return result.getString(1);
      }
    }
  }

  private static void seedMarket(ConnectionProvider connections, TableNames tables,
                                 MarketRules rules) throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try (PreparedStatement market = connection.prepareStatement(
          "INSERT INTO " + tables.markets()
              + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
              + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)");
           PreparedStatement state = connection.prepareStatement(
               "INSERT INTO " + tables.marketState()
                   + " (market_id,status,priority_sequence,match_sequence,reference_price,"
                   + "last_price,halted_until,version) VALUES (?,?,?,?,?,?,?,?)")) {
        market.setString(1, rules.marketId());
        market.setString(2, rules.currencyId());
        market.setString(3, "diamond");
        market.setString(4, "{}");
        market.setString(5, "{}");
        market.setString(6, "{}");
        market.setString(7, "{}");
        market.setLong(8, 1);
        market.setLong(9, 1);
        market.setLong(10, Instant.now().toEpochMilli());
        market.executeUpdate();

        state.setString(1, rules.marketId());
        state.setString(2, "OPEN");
        state.setLong(3, 0);
        state.setLong(4, 0);
        state.setString(5, rules.basePrice().toPlainString());
        state.setNull(6, java.sql.Types.DECIMAL);
        state.setNull(7, java.sql.Types.BIGINT);
        state.setLong(8, 0);
        state.executeUpdate();
        connection.commit();
      } catch (SQLException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }
}
