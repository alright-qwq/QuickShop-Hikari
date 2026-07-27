package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * Applies the version-one schema and records the version only after every table and index exists.
 *
 * <p>SQLite executes the migration as one transaction. MySQL implicitly commits DDL, so recovery is
 * forward-only: every DDL statement and index check is idempotent, and a retry resumes a partially
 * applied migration before inserting the version row.</p>
 */
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
