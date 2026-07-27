package com.ghostchu.quickshop.addon.exchange.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;

/** Holds MySQL's advisory writer lock on a dedicated connection for runtime lifetime. */
public final class MySqlSingleWriterGuard implements SingleWriterGuard {
  private final ConnectionFactory connections;
  private final String lockName;
  private Connection connection;

  public MySqlSingleWriterGuard(ConnectionFactory connections, String databasePrefix) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.lockName = Objects.requireNonNull(databasePrefix, "databasePrefix") + "exchange_writer";
  }

  @Override
  public synchronized void acquire() throws Exception {
    if (held()) throw new IllegalStateException("exchange writer lock is already held");
    Connection candidate = connections.open();
    try (PreparedStatement statement = candidate.prepareStatement("SELECT GET_LOCK(?, 0)")) {
      statement.setString(1, lockName);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) {
          candidate.close();
          throw new IllegalStateException("exchange writer lock is held by another server");
        }
      }
    }
    connection = candidate;
  }

  @Override
  public synchronized boolean held() {
    try {
      return connection != null && !connection.isClosed();
    } catch (Exception ignored) {
      return false;
    }
  }

  @Override
  public synchronized void close() throws Exception {
    if (connection == null) return;
    try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, lockName);
      statement.execute();
    } finally {
      connection.close();
      connection = null;
    }
  }

  @FunctionalInterface
  public interface ConnectionFactory {
    Connection open() throws Exception;
  }
}
