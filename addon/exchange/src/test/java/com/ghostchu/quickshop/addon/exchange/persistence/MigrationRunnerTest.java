package com.ghostchu.quickshop.addon.exchange.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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

  @Test
  void mysqlBalanceChecksUseDirectDecimalComparisons() {
    TableNames names = new TableNames("qs_");

    assertThat(SchemaV1.statements(SqlDialect.MYSQL, names))
        .anySatisfy(statement -> assertThat(statement).contains(
            "available DECIMAL(38,18) NOT NULL CHECK (available >= 0)",
            "frozen DECIMAL(38,18) NOT NULL CHECK (frozen >= 0)"));
  }

  @Test
  void rejectsNegativeReservedQuantityTradeQuantityAndCandleVolume(@TempDir Path temp)
      throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames names = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, names).migrate();

    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.executeUpdate(
          "INSERT INTO " + names.orders() + " (order_id,request_id,market_id,account_id,side,"
              + "order_type,time_in_force,original_quantity,remaining_quantity,status,"
              + "priority_sequence,config_version,fee_version,reserved_currency,reserved_quantity,"
              + "created_at,updated_at,version) VALUES ('o','r','m','a','BUY','LIMIT','GTC',"
              + "1,1,'OPEN',1,1,1,'1.00',-1,1,1,0)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate(
          "INSERT INTO " + names.trades() + " (trade_id,market_id,maker_order_id,taker_order_id,"
              + "buyer_account_id,seller_account_id,price,quantity,maker_fee,taker_fee,"
              + "match_sequence,executed_at) VALUES ('t','m','maker','taker','buyer','seller',"
              + "'1.00',-1,'0.00','0.00',1,1)"))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.executeUpdate(
          "INSERT INTO " + names.candles1m() + " (market_id,bucket_start,open_price,high_price,"
              + "low_price,close_price,volume,notional) VALUES ('m',1,'1.00','1.00','1.00',"
              + "'1.00',-1,'1.00')"))
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
