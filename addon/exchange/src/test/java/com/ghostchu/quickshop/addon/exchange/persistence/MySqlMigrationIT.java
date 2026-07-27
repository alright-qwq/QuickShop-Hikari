package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MySqlMigrationIT {
  @Container
  private static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @Test
  void migratesMysqlSchemaIdempotentlyAndRejectsNegativeBalance() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("qs_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.MYSQL, names);
    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(tableCount(connection, "qs_exchange_%")).isEqualTo(13);
      assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(1);
      assertThat(indexExists(connection, names.orders(), names.prefix() + "exchange_orders_book_idx"))
          .isTrue();
      assertThat(indexExists(connection, names.trades(), names.prefix() + "exchange_trades_time_idx"))
          .isTrue();
      assertThatThrownBy(() -> connection.createStatement().executeUpdate(
          "INSERT INTO " + names.accounts()
              + " (account_id,currency_id,available,frozen,version) VALUES "
              + "('a','USD','-1.00','0.00',0)"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void resumesPartiallyAppliedMysqlDdlBeforeRecordingVersion() throws Exception {
    ConnectionProvider connections = () -> DriverManager.getConnection(
        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    TableNames names = new TableNames("recover_");
    MigrationRunner runner = new MigrationRunner(connections, SqlDialect.MYSQL, names);

    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + names.markets() + " (market_id VARCHAR(128))");
    }

    assertThatThrownBy(runner::migrate).isInstanceOf(SQLException.class);
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      assertThat(tableCount(connection, "recover_exchange_%")).isEqualTo(2);
      assertThat(rowCount(connection, names.schemaVersion())).isZero();
      statement.execute("DROP TABLE " + names.markets());
    }

    runner.migrate();
    runner.migrate();

    try (Connection connection = connections.open()) {
      assertThat(tableCount(connection, "recover_exchange_%")).isEqualTo(13);
      assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(1);
      assertThat(indexExists(connection, names.orders(), names.prefix() + "exchange_orders_book_idx"))
          .isTrue();
      assertThat(indexExists(connection, names.trades(), names.prefix() + "exchange_trades_time_idx"))
          .isTrue();
    }
  }

  private static int tableCount(Connection connection, String pattern) throws SQLException {
    int count = 0;
    try (ResultSet result = connection.getMetaData().getTables(null, null, pattern, null)) {
      while (result.next()) count++;
    }
    return count;
  }

  private static int rowCount(Connection connection, String table) throws SQLException {
    try (ResultSet result = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  private static boolean indexExists(Connection connection, String table, String index)
      throws SQLException {
    try (ResultSet result = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
      while (result.next()) {
        if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }
}
