package com.ghostchu.quickshop.addon.exchange.persistence;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTransactionFenceTest {
  @Test
  void rejectsAStaleWriterBeforeDomainWorkRuns(@TempDir Path temp) throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("exchange.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    AtomicBoolean domainWorkRan = new AtomicBoolean();
    TransactionFence stale = connection -> {
      throw new SQLException("stale exchange writer epoch");
    };
    JdbcExchangeRepository repository =
        new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables, stale);

    assertThatThrownBy(() -> repository.inTransaction(transaction -> {
      domainWorkRan.set(true);
      return null;
    }))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("stale exchange writer epoch");
    assertThat(domainWorkRan).isFalse();
  }
}
