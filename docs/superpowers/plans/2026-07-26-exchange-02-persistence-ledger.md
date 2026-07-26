# Exchange Phase 2 Persistence and Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 将 Phase 1 的确定性撮合结果原子持久化到 SQLite 或 MySQL，并提供可恢复订单簿、版本化账户/库存、不可修改复式账本和数据库级 requestId 幂等。

**Architecture:** core 继续只依赖仓储端口；persistence 包以 JDBC Connection 为事务边界，并通过 SqlDialect 处理 SQLite 单写事务与 MySQL 行锁差异。数据库是最终事实来源：订单、资产、成交、费用和账本在同一事务提交，内存订单簿只在提交成功后更新。

**Tech Stack:** Java 21、JDBC、QuickShop EasySQL 提供的 Connection、SQLite 3、MySQL 8.4+、JUnit Jupiter、Testcontainers MySQL

---

## 前置条件与文件结构

先完成并验证 docs/superpowers/plans/2026-07-26-exchange-01-matching-engine.md。

- Modify: addon/exchange/pom.xml — 加入 SQLite/MySQL 测试驱动和 Testcontainers。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/ConnectionProvider.java — 解耦 EasySQL 的连接供应端口。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SqlDialect.java — SQLite/MySQL SQL 差异。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/TableNames.java — 验证 QuickShop 表前缀并生成全表名。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java — 版本化建表事务。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV1.java — 十三张表、索引与约束。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/*.java — 账户、库存、订单、成交、请求结果、行情和转账仓储端口。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java — JDBC 事务实现。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/*.java — 复式账本模型、校验与核对。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java — 风控、冻结、撮合、结算的事务协调器。
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/*.java — SQLite、MySQL、并发、恢复和故障注入。

### Task 1: 建立 JDBC 方言与迁移测试环境

**Files:**
- Modify: addon/exchange/pom.xml
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/ConnectionProvider.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SqlDialect.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/TableNames.java
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/SqliteTestDatabase.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/TableNamesTest.java

- [ ] **Step 1: 写表前缀验证红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableNamesTest {
  @Test
  void appliesValidatedQuickShopPrefix() {
    TableNames names = new TableNames("qs_");
    assertThat(names.orders()).isEqualTo("qs_exchange_orders");
    assertThat(names.schemaVersion()).isEqualTo("qs_exchange_schema_version");
  }

  @Test
  void rejectsSqlInPrefix() {
    assertThatThrownBy(() -> new TableNames("qs_; DROP TABLE users;--"))
        .hasMessage("invalid table prefix");
  }
}
~~~

- [ ] **Step 2: 运行并确认持久化类型缺失**

Run: mvn -pl addon/exchange -Dtest=TableNamesTest test

Expected: FAIL，编译器报告 TableNames 不存在。

- [ ] **Step 3: 加入测试依赖和连接端口**

在 addon/exchange/pom.xml dependencies 内加入：

~~~xml
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.50.3.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
  <version>9.4.0</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mysql</artifactId>
  <version>1.21.3</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>1.21.3</version>
  <scope>test</scope>
</dependency>
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface ConnectionProvider {
  Connection open() throws SQLException;
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

public enum SqlDialect {
  SQLITE("INTEGER", "TEXT"),
  MYSQL("BIGINT", "VARCHAR(36)");

  private final String longType;
  private final String uuidType;

  SqlDialect(String longType, String uuidType) {
    this.longType = longType;
    this.uuidType = uuidType;
  }

  public String longType() { return longType; }
  public String uuidType() { return uuidType; }
  public String forUpdate() { return this == MYSQL ? " FOR UPDATE" : ""; }
  public String decimalType() { return this == MYSQL ? "DECIMAL(38,18)" : "TEXT"; }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

public record TableNames(String prefix) {
  public TableNames {
    if (prefix == null || !prefix.matches("[A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("invalid table prefix");
    }
  }
  public String schemaVersion() { return prefix + "exchange_schema_version"; }
  public String markets() { return prefix + "exchange_markets"; }
  public String marketState() { return prefix + "exchange_market_state"; }
  public String accounts() { return prefix + "exchange_accounts"; }
  public String inventory() { return prefix + "exchange_inventory"; }
  public String orders() { return prefix + "exchange_orders"; }
  public String trades() { return prefix + "exchange_trades"; }
  public String journals() { return prefix + "exchange_ledger_journals"; }
  public String entries() { return prefix + "exchange_ledger_entries"; }
  public String transfers() { return prefix + "exchange_transfers"; }
  public String requestResults() { return prefix + "exchange_request_results"; }
  public String candles1m() { return prefix + "exchange_candles_1m"; }
  public String auditAlerts() { return prefix + "exchange_audit_alerts"; }
}
~~~

SQLite 测试工具每个测试使用独立临时文件，避免共享内存连接关闭后丢库：

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;

final class SqliteTestDatabase {
  private SqliteTestDatabase() {}
  static ConnectionProvider at(Path file) {
    return () -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
  }
}
~~~

- [ ] **Step 4: 运行前缀测试**

Run: mvn -pl addon/exchange -Dtest=TableNamesTest test

Expected: PASS，2 tests passed。

- [ ] **Step 5: 提交 JDBC 基础**

~~~bash
git add addon/exchange/pom.xml addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence
git commit -m "feat(exchange): add jdbc persistence foundation"
~~~

### Task 2: 创建版本化 SQLite/MySQL Schema

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV1.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java

- [ ] **Step 1: 写建表、幂等迁移和约束红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRunnerTest {
  @Test
  void createsAllTablesOnceAndEnforcesNonNegativeBalance(@TempDir Path temp) throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames names = new TableNames("qs_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.SQLITE, names);
    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(tableCount(connection, "qs_exchange_%")).isEqualTo(13);
      assertThatThrownBy(() -> connection.createStatement().executeUpdate(
          "INSERT INTO " + names.accounts()
              + " (account_id,currency_id,available,frozen,version) VALUES "
              + "('a','USD','-1.00','0.00',0)"))
          .isInstanceOf(SQLException.class);
    }
  }

  private static int tableCount(Connection connection, String pattern) throws SQLException {
    int count = 0;
    try (ResultSet result = connection.getMetaData().getTables(null, null, pattern, null)) {
      while (result.next()) count++;
    }
    return count;
  }
}
~~~

- [ ] **Step 2: 运行并确认迁移器缺失**

Run: mvn -pl addon/exchange -Dtest=MigrationRunnerTest test

Expected: FAIL，编译器报告 MigrationRunner 不存在。

- [ ] **Step 3: 写完整 V1 表定义**

SchemaV1.statements 返回以下十三张表及必要索引。所有 amount/price 在 Java 中以 BigDecimal 读写；SQLite 用规范十进制字符串，MySQL 用 DECIMAL(38,18)。

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

public final class SchemaV1 {
  private SchemaV1() {}

  public static List<String> statements(SqlDialect d, TableNames t) {
    String id = d.uuidType();
    String amount = d.decimalType();
    String number = d.longType();
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + t.schemaVersion()
            + " (version INTEGER PRIMARY KEY, applied_at " + number + " NOT NULL)",
        "CREATE TABLE IF NOT EXISTS " + t.markets()
            + " (market_id VARCHAR(128) PRIMARY KEY, currency_id VARCHAR(64) NOT NULL,"
            + " item_fingerprint TEXT NOT NULL, item_template TEXT NOT NULL,"
            + " structural_payload TEXT NOT NULL, fee_schedule_payload TEXT NOT NULL,"
            + " risk_payload TEXT NOT NULL,"
            + " structural_version " + number + " NOT NULL, risk_version " + number + " NOT NULL,"
            + " created_at " + number + " NOT NULL)",
        "CREATE TABLE IF NOT EXISTS " + t.marketState()
            + " (market_id VARCHAR(128) PRIMARY KEY, status VARCHAR(16) NOT NULL,"
            + " priority_sequence " + number + " NOT NULL, match_sequence " + number + " NOT NULL,"
            + " reference_price " + amount + " NOT NULL, last_price " + amount + ","
            + " halted_until " + number + ", version " + number + " NOT NULL,"
            + " FOREIGN KEY (market_id) REFERENCES " + t.markets() + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.accounts()
            + " (account_id " + id + " NOT NULL, currency_id VARCHAR(64) NOT NULL,"
            + " available " + amount + " NOT NULL CHECK (CAST(available AS NUMERIC) >= 0),"
            + " frozen " + amount + " NOT NULL CHECK (CAST(frozen AS NUMERIC) >= 0),"
            + " version " + number + " NOT NULL, PRIMARY KEY (account_id,currency_id))",
        "CREATE TABLE IF NOT EXISTS " + t.inventory()
            + " (account_id " + id + " NOT NULL, market_id VARCHAR(128) NOT NULL,"
            + " available_quantity " + number + " NOT NULL CHECK (available_quantity >= 0),"
            + " frozen_quantity " + number + " NOT NULL CHECK (frozen_quantity >= 0),"
            + " version " + number + " NOT NULL, PRIMARY KEY (account_id,market_id))",
        "CREATE TABLE IF NOT EXISTS " + t.orders()
            + " (order_id " + id + " PRIMARY KEY, request_id " + id + " NOT NULL,"
            + " market_id VARCHAR(128) NOT NULL, account_id " + id + " NOT NULL,"
            + " side VARCHAR(4) NOT NULL, order_type VARCHAR(8) NOT NULL,"
            + " time_in_force VARCHAR(3) NOT NULL, limit_price " + amount + ","
            + " slippage_boundary " + amount + ", original_quantity " + number + " NOT NULL,"
            + " remaining_quantity " + number + " NOT NULL,"
            + " status VARCHAR(24) NOT NULL, priority_sequence " + number + " NOT NULL,"
            + " config_version " + number + " NOT NULL, fee_version " + number + " NOT NULL,"
            + " reserved_currency " + amount + " NOT NULL, reserved_quantity " + number + " NOT NULL,"
            + " created_at " + number + " NOT NULL, updated_at " + number + " NOT NULL,"
            + " version " + number + " NOT NULL,"
            + " CHECK (remaining_quantity >= 0 AND remaining_quantity <= original_quantity),"
            + " UNIQUE (market_id,priority_sequence), UNIQUE (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.trades()
            + " (trade_id " + id + " PRIMARY KEY, market_id VARCHAR(128) NOT NULL,"
            + " maker_order_id " + id + " NOT NULL, taker_order_id " + id + " NOT NULL,"
            + " buyer_account_id " + id + " NOT NULL, seller_account_id " + id + " NOT NULL,"
            + " price " + amount + " NOT NULL, quantity " + number + " NOT NULL,"
            + " maker_fee " + amount + " NOT NULL, taker_fee " + amount + " NOT NULL,"
            + " match_sequence " + number + " NOT NULL, executed_at " + number + " NOT NULL,"
            + " UNIQUE (market_id,match_sequence))",
        "CREATE TABLE IF NOT EXISTS " + t.journals()
            + " (journal_id " + id + " PRIMARY KEY, journal_type VARCHAR(32) NOT NULL,"
            + " reference_id " + id + " NOT NULL, created_at " + number + " NOT NULL,"
            + " reversal_of " + id + ", UNIQUE (journal_type,reference_id))",
        "CREATE TABLE IF NOT EXISTS " + t.entries()
            + " (entry_id " + id + " PRIMARY KEY, journal_id " + id + " NOT NULL,"
            + " account_code VARCHAR(160) NOT NULL, asset_id VARCHAR(160) NOT NULL,"
            + " amount " + amount + " NOT NULL, created_at " + number + " NOT NULL,"
            + " FOREIGN KEY (journal_id) REFERENCES " + t.journals() + "(journal_id))",
        "CREATE TABLE IF NOT EXISTS " + t.transfers()
            + " (transfer_id " + id + " PRIMARY KEY, request_id " + id + " NOT NULL,"
            + " account_id " + id + " NOT NULL, transfer_type VARCHAR(32) NOT NULL,"
            + " asset_id VARCHAR(160) NOT NULL, amount " + amount + " NOT NULL,"
            + " status VARCHAR(24) NOT NULL, external_marker VARCHAR(128),"
            + " failure_reason TEXT, created_at " + number + " NOT NULL,"
            + " updated_at " + number + " NOT NULL, version " + number + " NOT NULL,"
            + " UNIQUE (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.requestResults()
            + " (account_id " + id + " NOT NULL, request_id " + id + " NOT NULL,"
            + " operation VARCHAR(32) NOT NULL, result_payload TEXT NOT NULL,"
            + " created_at " + number + " NOT NULL, PRIMARY KEY (account_id,request_id))",
        "CREATE TABLE IF NOT EXISTS " + t.candles1m()
            + " (market_id VARCHAR(128) NOT NULL, bucket_start " + number + " NOT NULL,"
            + " open_price " + amount + " NOT NULL, high_price " + amount + " NOT NULL,"
            + " low_price " + amount + " NOT NULL, close_price " + amount + " NOT NULL,"
            + " volume " + number + " NOT NULL, notional " + amount + " NOT NULL,"
            + " PRIMARY KEY (market_id,bucket_start))",
        "CREATE TABLE IF NOT EXISTS " + t.auditAlerts()
            + " (alert_id " + id + " PRIMARY KEY, market_id VARCHAR(128),"
            + " account_id " + id + ", alert_type VARCHAR(48) NOT NULL,"
            + " severity VARCHAR(16) NOT NULL, payload TEXT NOT NULL,"
            + " created_at " + number + " NOT NULL, acknowledged_at " + number + ")");
  }

  public static List<IndexDefinition> indexes(TableNames t) {
    return List.of(
        new IndexDefinition(t.prefix() + "exchange_orders_book_idx", t.orders(),
            "market_id,status,side,limit_price,priority_sequence"),
        new IndexDefinition(t.prefix() + "exchange_trades_time_idx", t.trades(),
            "market_id,executed_at"));
  }

  public record IndexDefinition(String name, String table, String columns) {}
}
~~~

- [ ] **Step 4: 实现只执行一次的事务迁移器**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.*;
import java.time.Instant;

public final class MigrationRunner {
  private final ConnectionProvider connections;
  private final SqlDialect dialect;
  private final TableNames tables;

  public MigrationRunner(ConnectionProvider connections, SqlDialect dialect, TableNames tables) {
    this.connections = connections;
    this.dialect = dialect;
    this.tables = tables;
  }

  public void migrate() throws SQLException {
    try (Connection connection = connections.open()) {
      connection.setAutoCommit(false);
      try {
        for (String sql : SchemaV1.statements(dialect, tables)) {
          try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
          }
        }
        for (SchemaV1.IndexDefinition index : SchemaV1.indexes(tables)) {
          ensureIndex(connection, index);
        }
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO " + tables.schemaVersion()
                + " (version,applied_at) SELECT 1,? WHERE NOT EXISTS "
                + "(SELECT 1 FROM " + tables.schemaVersion() + " WHERE version=1)")) {
          insert.setLong(1, Instant.now().toEpochMilli());
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException failure) {
        connection.rollback();
        throw failure;
      }
    }
  }

  private static void ensureIndex(Connection connection, SchemaV1.IndexDefinition index)
      throws SQLException {
    boolean exists = false;
    try (ResultSet result = connection.getMetaData()
        .getIndexInfo(null, null, index.table(), false, false)) {
      while (result.next()) {
        if (index.name().equalsIgnoreCase(result.getString("INDEX_NAME"))) {
          exists = true;
          break;
        }
      }
    }
    if (!exists) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE INDEX " + index.name() + " ON "
            + index.table() + " (" + index.columns() + ")");
      }
    }
  }
}
~~~

- [ ] **Step 5: 运行迁移测试**

Run: mvn -pl addon/exchange -Dtest=MigrationRunnerTest test

Expected: PASS；重复 migrate 不重复版本，十三张逻辑表存在，负余额插入失败。

- [ ] **Step 6: 提交 schema**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV1.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java
git commit -m "feat(exchange): create versioned exchange schema"
~~~

### Task 3: 实现版本化账户、库存和数据库幂等

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/CurrencyBalance.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ItemBalance.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeRepository.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/StoredRequestResult.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcBalanceRepositoryTest.java

- [ ] **Step 1: 写冻结、版本冲突和 requestId 红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcBalanceRepositoryTest {
  @Test
  void freezesWithoutGoingNegativeAndDeduplicatesRequest(@TempDir Path temp) throws Exception {
    ConnectionProvider cp = SqliteTestDatabase.at(temp.resolve("balance.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(cp, SqlDialect.SQLITE, tables).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(cp, SqlDialect.SQLITE, tables);
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();

    repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(account, "USD", new BigDecimal("100.00"));
      tx.freezeCurrency(account, "USD", new BigDecimal("60.00"));
      tx.putRequestResult(new StoredRequestResult(account, request, "PLACE", "{\"order\":\"one\"}"));
      return null;
    });

    CurrencyBalance balance = repository.inTransaction(tx -> tx.currency(account, "USD"));
    assertThat(balance.available()).isEqualByComparingTo("40.00");
    assertThat(balance.frozen()).isEqualByComparingTo("60.00");
    assertThat(repository.inTransaction(tx -> tx.requestResult(account, request))).isPresent();
    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.freezeCurrency(account, "USD", new BigDecimal("41.00"));
      return null;
    })).isInstanceOf(InsufficientAssetsException.class);
  }
}
~~~

- [ ] **Step 2: 运行并确认仓储端口缺失**

Run: mvn -pl addon/exchange -Dtest=JdbcBalanceRepositoryTest test

Expected: FAIL，编译器报告 ExchangeRepository 等类型不存在。

- [ ] **Step 3: 定义不可变余额与事务端口**

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record CurrencyBalance(UUID accountId, String currencyId,
                              BigDecimal available, BigDecimal frozen, long version) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import java.util.UUID;

public record ItemBalance(UUID accountId, String marketId,
                          long availableQuantity, long frozenQuantity, long version) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import java.util.UUID;

public record StoredRequestResult(UUID accountId, UUID requestId,
                                  String operation, String payload) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

public final class InsufficientAssetsException extends RuntimeException {
  public InsufficientAssetsException(String asset) {
    super("insufficient " + asset);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;

public interface ExchangeTransaction {
  CurrencyBalance currency(UUID accountId, String currencyId) throws SQLException;
  ItemBalance inventory(UUID accountId, String marketId) throws SQLException;
  void creditAvailableCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void freezeCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void releaseCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void consumeFrozenCurrency(UUID accountId, String currencyId, BigDecimal amount) throws SQLException;
  void creditAvailableItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void freezeItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void releaseItems(UUID accountId, String marketId, long quantity) throws SQLException;
  void consumeFrozenItems(UUID accountId, String marketId, long quantity) throws SQLException;
  Optional<StoredRequestResult> requestResult(UUID accountId, UUID requestId) throws SQLException;
  void putRequestResult(StoredRequestResult result) throws SQLException;
  void insertOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity) throws SQLException;
  void updateOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity, long expectedVersion) throws SQLException;
  void insertTrade(Trade trade) throws SQLException;
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import java.sql.SQLException;

public interface ExchangeRepository {
  <T> T inTransaction(TransactionWork<T> work) throws SQLException;

  @FunctionalInterface
  interface TransactionWork<T> {
    T apply(ExchangeTransaction transaction) throws SQLException;
  }
}
~~~

- [ ] **Step 4: 实现 JDBC 锁定、upsert 和版本更新**

JdbcExchangeRepository.inTransaction 必须：

~~~java
public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
  try (Connection connection = connections.open()) {
    if (dialect == SqlDialect.SQLITE) {
      try (Statement begin = connection.createStatement()) {
        begin.execute("BEGIN IMMEDIATE");
      }
    } else {
      connection.setAutoCommit(false);
    }
    try {
      T result = work.apply(new JdbcTransaction(connection, dialect, tables));
      if (dialect == SqlDialect.SQLITE) {
        try (Statement commit = connection.createStatement()) {
          commit.execute("COMMIT");
        }
      } else {
        connection.commit();
      }
      return result;
    } catch (SQLException | RuntimeException failure) {
      if (dialect == SqlDialect.SQLITE) {
        try (Statement rollback = connection.createStatement()) {
          rollback.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      } else {
        connection.rollback();
      }
      throw failure;
    }
  }
}
~~~

JdbcTransaction.currency 先插入零余额行；MySQL 的 SELECT 查询末尾追加 FOR UPDATE，SQLite 依赖 BEGIN IMMEDIATE；所有金额 UPDATE 使用 version 条件和 Java 计算后的非负值：

~~~java
CurrencyBalance before = currency(accountId, currencyId);
if (before.available().compareTo(amount) < 0) {
  throw new InsufficientAssetsException("currency");
}
BigDecimal available = before.available().subtract(amount);
BigDecimal frozen = before.frozen().add(amount);
try (PreparedStatement update = connection.prepareStatement(
    "UPDATE " + tables.accounts()
        + " SET available=?,frozen=?,version=version+1"
        + " WHERE account_id=? AND currency_id=? AND version=?")) {
  writeDecimal(update, 1, available);
  writeDecimal(update, 2, frozen);
  update.setString(3, accountId.toString());
  update.setString(4, currencyId);
  update.setLong(5, before.version());
  if (update.executeUpdate() != 1) throw new ConcurrentModificationException("currency version changed");
}
~~~

在同一个 JdbcTransaction 内实现其余七个余额操作，遵守以下精确变换：

- creditAvailableCurrency: available += amount
- releaseCurrency: frozen -= amount; available += amount
- consumeFrozenCurrency: frozen -= amount
- creditAvailableItems: availableQuantity += quantity
- freezeItems: availableQuantity -= quantity; frozenQuantity += quantity
- releaseItems: frozenQuantity -= quantity; availableQuantity += quantity
- consumeFrozenItems: frozenQuantity -= quantity

amount/quantity 必须大于零；减法前检查来源字段；每次 UPDATE 都带 version 条件。putRequestResult 依赖 (account_id,request_id) 主键，唯一冲突时读取并返回既有值的逻辑放入上层 PersistentOrderService。

- [ ] **Step 5: 运行余额测试**

Run: mvn -pl addon/exchange -Dtest=JdbcBalanceRepositoryTest test

Expected: PASS；100.00 冻结 60.00 后为 40.00/60.00，超冻回滚且原状态不变。

- [ ] **Step 6: 提交账户仓储**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcBalanceRepositoryTest.java
git commit -m "feat(exchange): persist versioned exchange balances"
~~~

### Task 4: 实现不可修改复式账本

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/LedgerEntry.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/LedgerJournal.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/LedgerValidator.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcLedgerTest.java

- [ ] **Step 1: 写不平衡拒绝、追加和反向分录红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.ledger.*;
import com.ghostchu.quickshop.addon.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcLedgerTest {
  @Test
  void acceptsBalancedJournalAndAppendsReversal(@TempDir Path temp) throws Exception {
    ConnectionProvider cp = SqliteTestDatabase.at(temp.resolve("ledger.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(cp, SqlDialect.SQLITE, names).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(cp, SqlDialect.SQLITE, names);
    LedgerJournal journal = journal("10.00", null);

    repository.inTransaction(tx -> { tx.appendJournal(journal); return null; });
    LedgerJournal reversal = journal("-10.00", journal.journalId());
    repository.inTransaction(tx -> { tx.appendJournal(reversal); return null; });

    assertThatThrownBy(() -> repository.inTransaction(tx -> {
      tx.appendJournal(new LedgerJournal(UUID.randomUUID(), "BROKEN", UUID.randomUUID(),
          Instant.EPOCH, null, List.of(new LedgerEntry(UUID.randomUUID(), "player:a", "USD",
          BigDecimal.ONE, Instant.EPOCH))));
      return null;
    })).isInstanceOf(UnbalancedJournalException.class);
    assertThatThrownBy(() -> directUpdateFirstEntry(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
    assertThatThrownBy(() -> directDeleteFirstEntry(cp, names))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("immutable ledger");
  }

  private static LedgerJournal journal(String amount, UUID reversalOf) {
    BigDecimal value = new BigDecimal(amount);
    return new LedgerJournal(UUID.randomUUID(), "ADJUSTMENT", UUID.randomUUID(),
        Instant.EPOCH, reversalOf, List.of(
        new LedgerEntry(UUID.randomUUID(), "player:a", "USD", value, Instant.EPOCH),
        new LedgerEntry(UUID.randomUUID(), "custody:USD", "USD", value.negate(), Instant.EPOCH)));
  }

  private static void directUpdateFirstEntry(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var update = connection.prepareStatement(
             "UPDATE " + names.entries() + " SET amount='999.00'")) {
      update.executeUpdate();
    }
  }

  private static void directDeleteFirstEntry(ConnectionProvider cp, TableNames names)
      throws java.sql.SQLException {
    try (var connection = cp.open();
         var delete = connection.prepareStatement("DELETE FROM " + names.entries())) {
      delete.executeUpdate();
    }
  }
}
~~~

- [ ] **Step 2: 运行并确认 ledger 类型缺失**

Run: mvn -pl addon/exchange -Dtest=JdbcLedgerTest test

Expected: FAIL，编译器报告 LedgerJournal 等类型不存在。

- [ ] **Step 3: 定义账本与逐资产平衡校验**

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(UUID entryId, String accountCode, String assetId,
                          BigDecimal amount, Instant createdAt) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerJournal(UUID journalId, String journalType, UUID referenceId,
                            Instant createdAt, UUID reversalOf, List<LedgerEntry> entries) {
  public LedgerJournal {
    entries = List.copyOf(entries);
    LedgerValidator.requireBalanced(entries);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

public final class UnbalancedJournalException extends IllegalArgumentException {
  public UnbalancedJournalException(String asset) {
    super("journal is not balanced for asset " + asset);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.util.*;

public final class LedgerValidator {
  private LedgerValidator() {}
  public static void requireBalanced(List<LedgerEntry> entries) {
    if (entries.size() < 2) throw new UnbalancedJournalException("missing counter-entry");
    Map<String, BigDecimal> totals = new HashMap<>();
    for (LedgerEntry entry : entries) {
      totals.merge(entry.assetId(), entry.amount(), BigDecimal::add);
    }
    totals.forEach((asset, total) -> {
      if (total.signum() != 0) throw new UnbalancedJournalException(asset);
    });
  }
}
~~~

- [ ] **Step 4: 仅追加 journals/entries**

在 ExchangeTransaction 加入：

~~~java
void appendJournal(com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal journal)
    throws java.sql.SQLException;
~~~

JdbcTransaction.appendJournal 先 INSERT journal，再批量 INSERT entries；不要提供 UPDATE/DELETE 账本方法。唯一冲突直接抛出 SQLException，确保 journalId 或 (journalType,referenceId) 不会重复记账。反向更正必须以新 journalId 插入，并把 reversal_of 指向原 journal。

SchemaV1 同时创建 entries 的 BEFORE UPDATE 与 BEFORE DELETE 保护触发器。SQLite 触发器执行 SELECT RAISE(ABORT,'immutable ledger')；MySQL 触发器执行 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable ledger'。MigrationRunner 像索引一样先从 DatabaseMetaData/INFORMATION_SCHEMA.TRIGGERS 检查名称再创建，保证重复 migrate 安全。directUpdateFirstEntry 使用独立 JDBC 连接执行真实 UPDATE，证明约束不只依赖 Java API。

- [ ] **Step 5: 运行账本和迁移回归**

Run: mvn -pl addon/exchange -Dtest=JdbcLedgerTest,MigrationRunnerTest test

Expected: PASS；不平衡分录在 SQL 写入前被拒绝，正向和反向 journal 均保留。

- [ ] **Step 6: 提交账本**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcLedgerTest.java
git commit -m "feat(exchange): add immutable double-entry ledger"
~~~

### Task 5: 原子提交订单、成交、资产与费用

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderRequest.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderReceipt.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/SettlementPlan.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderServiceTest.java

- [ ] **Step 1: 写成交原子性和费用账户红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentOrderServiceTest {
  @Test
  void commitsTradeAndReturnsSameReceiptForDuplicateRequest() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(10);
    UUID buyer = fixture.accountWithCurrency("1000.00");
    fixture.service().place(new OrderRequest(UUID.randomUUID(), seller, "diamond-usd",
        OrderSide.SELL, "LIMIT", new BigDecimal("100.00"), null, 2));
    UUID request = UUID.randomUUID();
    OrderRequest buy = new OrderRequest(request, buyer, "diamond-usd",
        OrderSide.BUY, "LIMIT", new BigDecimal("100.00"), null, 2);

    OrderReceipt first = fixture.service().place(buy);
    OrderReceipt duplicate = fixture.service().place(buy);

    assertThat(duplicate).isEqualTo(first);
    assertThat(first.trades()).hasSize(1);
    assertThat(fixture.tradeCount()).isEqualTo(1);
    assertThat(fixture.ledgerIsBalanced()).isTrue();
    assertThat(fixture.feeAccountBalance()).isPositive();
  }
}
~~~

- [ ] **Step 2: 运行并确认服务类型缺失**

Run: mvn -pl addon/exchange -Dtest=PersistentOrderServiceTest test

Expected: FAIL，编译器报告 OrderRequest、OrderReceipt 和 ExchangeServiceFixture 不存在。

- [ ] **Step 3: 定义稳定请求与回执**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderRequest(UUID requestId, UUID accountId, String marketId,
                           OrderSide side, String type, BigDecimal price,
                           BigDecimal slippageBoundary, long quantity) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.util.List;
import java.util.UUID;

public record OrderReceipt(UUID requestId, UUID orderId, String status, List<Trade> trades) {
  public OrderReceipt {
    trades = List.copyOf(trades);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import java.math.BigDecimal;
import java.util.List;

public record SettlementPlan(Order taker, List<Order> makers, List<Trade> trades,
                             BigDecimal takerCurrencyRelease, long takerItemRelease) {
  public SettlementPlan {
    makers = List.copyOf(makers);
    trades = List.copyOf(trades);
  }
}
~~~

- [ ] **Step 4: 实现事务协调顺序**

PersistentOrderService.place 的固定顺序：

1. 在市场串行执行器内进入 repository.inTransaction。
2. 查 (accountId,requestId)；存在则反序列化并直接返回。
3. 收集本次涉及的 (accountId,assetId)，按 UUID 字符串再按 assetId 排序，并严格按该顺序 SELECT FOR UPDATE；跨市场也使用同一 LockKey 排序，禁止按买卖方向决定锁顺序。
4. 根据 OrderRequest 创建 Order，使用 TimeOrderedIdGenerator 分配 orderId，并分配持久 priority_sequence。
5. BUY 冻结 ReservationCalculator 的最大货币；SELL 冻结数量。
6. 从事务快照撮合，逐成交：
   - 买方 consumeFrozenCurrency(notional + buyerFee)。
   - 买方 creditAvailableItems(quantity)。
   - 卖方 consumeFrozenItems(quantity)。
   - 卖方 creditAvailableCurrency(notional - sellerFee)。
   - 系统费用账户 creditAvailableCurrency(buyerFee + sellerFee)。
7. 更新 Maker，插入 Taker、Trade、逐资产平衡 LedgerJournal。
8. 每笔成交更新 ReferencePriceTracker/CircuitBreaker 的事务副本；触发一级或二级熔断时，把 market_state.status、halted_until 和熔断前 reference_price 与成交放在同一事务，二级同时插入 HIGH alert。
9. 释放已成交买单的限价差额与剩余 IOC 预留；释放 IOC 卖单余量。
10. 写 StoredRequestResult，提交 SQL。
11. 只有 commit 成功后才把 SettlementPlan、参考价和熔断状态应用到内存对象。
12. 若 commit 失败，将市场状态写为 RECOVERING 并调用 Phase 2 Task 6 的恢复器。

使用明确常量：

~~~java
public static final UUID FEE_ACCOUNT_ID =
    UUID.nameUUIDFromBytes("quickshop-exchange-fees".getBytes(java.nio.charset.StandardCharsets.UTF_8));
~~~

每笔 Trade 的货币 journal 至少包含 buyer liability、seller liability、fee liability 和 custody 四个账户；物品 journal 包含 seller item liability、buyer item liability 和 item custody。每种 assetId 的 signed amount 总和必须为零。

测试夹具 ExchangeServiceFixture 放入 test 源，使用真实 MigrationRunner、JdbcExchangeRepository、OrderBook 和 MatchingEngine，不 mock SQL；JSON 序列化固定字段顺序，重复请求必须逐字段相同。

- [ ] **Step 5: 运行原子结算测试**

Run: mvn -pl addon/exchange -Dtest=PersistentOrderServiceTest test

Expected: PASS；只有一笔 Trade、重复 requestId 不改变状态、费用账户增加且所有 journal 平衡。

- [ ] **Step 6: 提交事务订单服务**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service
git commit -m "feat(exchange): settle matched orders atomically"
~~~

### Task 6: 从数据库恢复订单簿和市场序列

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/MarketSnapshot.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeRepository.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryServiceTest.java

- [ ] **Step 1: 写重启保持优先级红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookRecoveryServiceTest {
  @Test
  void rebuildsOpenAndPartialOrdersInOriginalPriorityOrder() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.withTwoSamePriceSells();

    RecoveredMarket recovered =
        new OrderBookRecoveryService(fixture.repository()).recover("diamond-usd");
    Order first = recovered.book().best(
        com.ghostchu.quickshop.addon.exchange.core.model.OrderSide.SELL).orElseThrow();

    assertThat(first.prioritySequence()).isEqualTo(1);
    assertThat(recovered.nextPrioritySequence()).isEqualTo(3);
    assertThat(recovered.nextMatchSequence()).isGreaterThanOrEqualTo(1);
  }
}
~~~

- [ ] **Step 2: 运行并确认恢复类型缺失**

Run: mvn -pl addon/exchange -Dtest=OrderBookRecoveryServiceTest test

Expected: FAIL，编译器报告 OrderBookRecoveryService 和 RecoveredMarket 不存在。

- [ ] **Step 3: 添加只读恢复查询**

~~~java
package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import java.math.BigDecimal;
import java.util.List;

public record MarketSnapshot(String marketId, MarketStatus status,
                             long prioritySequence, long matchSequence,
                             BigDecimal referencePrice, List<Order> openOrders) {
  public MarketSnapshot {
    openOrders = List.copyOf(openOrders);
  }
}
~~~

ExchangeRepository 加入：

~~~java
MarketSnapshot loadMarket(String marketId) throws java.sql.SQLException;
java.util.List<String> listMarketIds() throws java.sql.SQLException;
~~~

查询条件必须是 status IN ('OPEN','PARTIALLY_FILLED')，排序必须是 side、limit_price 和 priority_sequence；Java 恢复器仍按 prioritySequence 升序 add，从而让每个同价 LinkedHashMap 保持原 FIFO。

- [ ] **Step 4: 实现恢复服务**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;

public record RecoveredMarket(OrderBook book, long nextPrioritySequence,
                              long nextMatchSequence) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.repository.*;

import java.sql.SQLException;
import java.util.Comparator;

public final class OrderBookRecoveryService {
  private final ExchangeRepository repository;

  public OrderBookRecoveryService(ExchangeRepository repository) {
    this.repository = repository;
  }

  public RecoveredMarket recover(String marketId) throws SQLException {
    MarketSnapshot snapshot = repository.loadMarket(marketId);
    OrderBook book = new OrderBook();
    snapshot.openOrders().stream()
        .sorted(Comparator.comparingLong(order -> order.prioritySequence()))
        .forEach(book::add);
    return new RecoveredMarket(book, snapshot.prioritySequence() + 1,
        snapshot.matchSequence() + 1);
  }

  public java.util.Map<String, RecoveredMarket> recoverAll() throws SQLException {
    java.util.LinkedHashMap<String, RecoveredMarket> recovered = new java.util.LinkedHashMap<>();
    for (String marketId : repository.listMarketIds()) {
      recovered.put(marketId, recover(marketId));
    }
    return java.util.Map.copyOf(recovered);
  }
}
~~~

- [ ] **Step 5: 运行恢复与全量 SQLite 测试**

Run: mvn -pl addon/exchange -Dtest=OrderBookRecoveryServiceTest,Jdbc*Test,PersistentOrderServiceTest test

Expected: PASS；重建后旧订单仍先成交，下一序列严格大于数据库已保存值。

- [ ] **Step 6: 提交恢复逻辑**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryService.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/RecoveredMarket.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryServiceTest.java
git commit -m "feat(exchange): recover order books from database"
~~~

### Task 7: 注入事务失败并验证完整回滚

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/SettlementStage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/SettlementObserver.java
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/InjectedFailure.java
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/SettlementFailureInjectionTest.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java

- [ ] **Step 1: 写每个提交阶段的故障注入测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementFailureInjectionTest {
  @ParameterizedTest
  @EnumSource(SettlementStage.class)
  void rollsBackEverySettlementStage(SettlementStage stage) throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.crossingOrders();
    ExchangeState before = fixture.readState();
    PersistentOrderService service = fixture.service(current -> {
      if (current == stage) throw new InjectedFailure(stage.name());
    });

    assertThatThrownBy(() -> fixture.executeCross(service))
        .isInstanceOf(InjectedFailure.class);
    fixture.assertDatabaseStateEquals(before);
    fixture.assertMarketRecovering();
  }
}
~~~

- [ ] **Step 2: 运行并确认阶段观察器缺失**

Run: mvn -pl addon/exchange -Dtest=SettlementFailureInjectionTest test

Expected: FAIL，编译器报告 SettlementStage、InjectedFailure 或 setObserver 不存在。

- [ ] **Step 3: 定义精确提交阶段和默认空观察器**

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

public enum SettlementStage {
  AFTER_RESERVATION,
  AFTER_ORDER_INSERT,
  AFTER_MAKER_UPDATE,
  AFTER_TRADE_INSERT,
  AFTER_BALANCE_UPDATE,
  AFTER_LEDGER_INSERT,
  AFTER_REQUEST_RESULT
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

@FunctionalInterface
public interface SettlementObserver {
  SettlementObserver NONE = stage -> {};
  void reached(SettlementStage stage);
}
~~~

测试源同时加入：

~~~java
package com.ghostchu.quickshop.addon.exchange.service;

final class InjectedFailure extends RuntimeException {
  InjectedFailure(String stage) {
    super(stage);
  }
}
~~~

PersistentOrderService 构造器接收 SettlementObserver，Main 最终装配固定传 NONE；测试夹具 service(observer) 用同一组仓储构造带故障观察器的实例。每个枚举位置都在同一 repository.inTransaction 回调内调用 observer.reached。任何异常都 rollback，随后使用独立短事务把 market_state.status 设为 RECOVERING。

- [ ] **Step 4: 运行故障注入测试**

Run: mvn -pl addon/exchange -Dtest=SettlementFailureInjectionTest test

Expected: PASS，七个提交点全部回到执行前数据库快照，受影响市场为 RECOVERING。

- [ ] **Step 5: 提交故障恢复保护**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/SettlementFailureInjectionTest.java
git commit -m "test(exchange): verify atomic settlement rollback"
~~~

### Task 8: 验证 MySQL 行锁、并发幂等和每日核对

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationReport.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MySqlRepositoryIT.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationServiceTest.java

- [ ] **Step 1: 写 MySQL 并发重复请求集成测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlRepositoryIT {
  @Test
  void concurrentDuplicateRequestCreatesOneOrder() throws Exception {
    try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
      mysql.start();
      ConnectionProvider cp = () -> DriverManager.getConnection(
          mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      ExchangeServiceFixture fixture =
          ExchangeServiceFixture.mysql(cp, new TableNames("qs_"));
      UUID request = UUID.randomUUID();
      var command = fixture.buyRequest(request);
      try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
        var futures = java.util.stream.IntStream.range(0, 32)
            .mapToObj(i -> executor.submit(() -> fixture.service().place(command))).toList();
        for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
      }
      assertThat(fixture.orderCountForRequest(request)).isEqualTo(1);
      assertThat(fixture.requestResultCount(request)).isEqualTo(1);
    }
  }

  @Test
  void opposingCrossMarketOperationsUseOneStableLockOrder() throws Exception {
    try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
      mysql.start();
      ExchangeServiceFixture fixture = ExchangeServiceFixture.mysql(
          () -> DriverManager.getConnection(
              mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()),
          new TableNames("qs_"));
      fixture.seedTwoAccountsAcrossTwoMarkets();
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<?> first = executor.submit(fixture::tradeAccountAToB);
        Future<?> second = executor.submit(fixture::tradeAccountBToA);
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
      }
      assertThat(fixture.reconciliation().balanced()).isTrue();
      assertThat(fixture.transactionRetryCount()).isLessThanOrEqualTo(3);
    }
  }
}
~~~

- [ ] **Step 2: 运行集成测试并确认方言差异**

Run: mvn -pl addon/exchange -Dtest=MySqlRepositoryIT test

Expected: 初次可能 FAIL 于 SQLite 专用 INSERT/CAST/BEGIN SQL；记录第一条实际 SQL 错误后，只在 SqlDialect 分支修正，不放宽唯一约束。

- [ ] **Step 3: 完成 MySQL upsert 和行锁实现**

MySQL 使用 INSERT INTO accounts(account_id,currency_id,available,frozen,version) VALUES (?,?,?,?,0) ON DUPLICATE KEY UPDATE account_id=account_id 建立零余额行，SELECT 余额/订单时追加 FOR UPDATE。SQLite 使用 INSERT OR IGNORE 且由 BEGIN IMMEDIATE 串行写。所有 UUID 以标准 36 字符串写入，两种方言的金额读出后都调用 new BigDecimal(resultSet.getString(column))，避免浮点转换。

- [ ] **Step 4: 写核对测试与服务**

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

import java.math.BigDecimal;
import java.util.Map;

public record ReconciliationReport(Map<String, BigDecimal> ledgerDifferences,
                                   Map<String, BigDecimal> custodyDifferences,
                                   int underReservedOrders) {
  public ReconciliationReport {
    ledgerDifferences = Map.copyOf(ledgerDifferences);
    custodyDifferences = Map.copyOf(custodyDifferences);
  }
  public boolean balanced() {
    return ledgerDifferences.values().stream().allMatch(v -> v.signum() == 0)
        && custodyDifferences.values().stream().allMatch(v -> v.signum() == 0)
        && underReservedOrders == 0;
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ledger;

import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.sql.SQLException;

public final class ReconciliationService {
  private final ExchangeRepository repository;
  public ReconciliationService(ExchangeRepository repository) {
    this.repository = repository;
  }
  public ReconciliationReport run() throws SQLException {
    return repository.reconcile();
  }
}
~~~

ExchangeRepository 增加 reconcile()，SQL 分三组聚合：

~~~java
com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport reconcile()
    throws java.sql.SQLException;
~~~

- entries 按 asset_id 求和，非零即 ledgerDifferences。
- custody 系统账户与 exchange_accounts/exchange_inventory 总负债比较，差值进入 custodyDifferences。
- OPEN/PARTIALLY_FILLED 订单逐一比较 reserved_currency/reserved_quantity 与其最坏履约需求，计入 underReservedOrders。

受影响市场的暂停动作不放在只读 ReconciliationService 内；Phase 4 运维任务读取 balanced=false 后将对应市场暂停并发最高级告警。

- [ ] **Step 5: 运行 SQLite、MySQL、核对和完整验证**

Run: mvn -pl addon/exchange -Dtest=ReconciliationServiceTest,MySqlRepositoryIT test

Expected: BUILD SUCCESS；MySQL 32 个并发重复命令只有一单一结果；相反方向的跨市场操作在 10 秒内完成且账本平衡；人为篡改测试数据后 ReconciliationReport.balanced 为 false。

Run: mvn -pl addon/exchange verify

Expected: BUILD SUCCESS，Phase 1 与 Phase 2 全部测试通过。

- [ ] **Step 6: 提交 MySQL 与核对**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange
git commit -m "feat(exchange): verify mysql concurrency and reconciliation"
~~~

## Phase 2 验收

Run: mvn -pl addon/exchange -Dtest='*Test,*IT' verify

Expected: SQLite 总是运行；Docker 可用时 MySQL 8.4 集成测试运行。每个 requestId、prioritySequence、matchSequence 由数据库唯一约束保护；任何结算失败不留下半笔订单、半笔成交或不平衡 journal；重启恢复保持原价格和时间优先级。
