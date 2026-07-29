package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/** Activates and validates durable writer generations for MySQL mutation transactions. */
public final class MySqlWriterEpoch {
  private static final String WRITER_ID = "exchange";

  private MySqlWriterEpoch() {}

  public static TransactionFence activate(
      Connection lockConnection, String advisoryLockName, TableNames tables) throws SQLException {
    Objects.requireNonNull(lockConnection, "lockConnection");
    Objects.requireNonNull(advisoryLockName, "advisoryLockName");
    Objects.requireNonNull(tables, "tables");
    requireAdvisoryLockOwner(lockConnection, advisoryLockName);

    boolean previousAutoCommit = lockConnection.getAutoCommit();
    lockConnection.setAutoCommit(false);
    try {
      long current = currentEpochForUpdate(lockConnection, tables);
      long activated;
      try {
        activated = Math.incrementExact(current);
      } catch (ArithmeticException overflow) {
        throw new SQLException("exchange writer epoch is exhausted", overflow);
      }
      try (PreparedStatement update = lockConnection.prepareStatement(
          "UPDATE " + tables.writerEpoch() + " SET epoch=? WHERE writer_id=? AND epoch=?")) {
        update.setLong(1, activated);
        update.setString(2, WRITER_ID);
        update.setLong(3, current);
        if (update.executeUpdate() != 1) {
          throw new SQLException("failed to activate exchange writer epoch");
        }
      }
      lockConnection.commit();
      return forEpoch(tables, activated);
    } catch (SQLException | RuntimeException failure) {
      try {
        lockConnection.rollback();
      } catch (SQLException rollbackFailure) {
        failure.addSuppressed(rollbackFailure);
      }
      throw failure;
    } finally {
      lockConnection.setAutoCommit(previousAutoCommit);
    }
  }

  public static TransactionFence forEpoch(TableNames tables, long epoch) {
    Objects.requireNonNull(tables, "tables");
    if (epoch <= 0) {
      throw new IllegalArgumentException("writer epoch must be positive");
    }
    return connection -> {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT epoch FROM " + tables.writerEpoch()
              + " WHERE writer_id=? AND epoch=? LOCK IN SHARE MODE")) {
        query.setString(1, WRITER_ID);
        query.setLong(2, epoch);
        try (ResultSet result = query.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("stale exchange writer epoch: " + epoch);
          }
        }
      }
    };
  }

  private static void requireAdvisoryLockOwner(
      Connection connection, String advisoryLockName) throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
      query.setString(1, advisoryLockName);
      try (ResultSet result = query.executeQuery()) {
        if (!result.next() || !result.getBoolean(1)) {
          throw new SQLException("advisory lock is not owned by the activation connection");
        }
      }
    }
  }

  private static long currentEpochForUpdate(Connection connection, TableNames tables)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT epoch FROM " + tables.writerEpoch()
            + " WHERE writer_id=? FOR UPDATE")) {
      query.setString(1, WRITER_ID);
      try (ResultSet result = query.executeQuery()) {
        if (!result.next()) {
          throw new SQLException("exchange writer epoch row is missing");
        }
        return result.getLong(1);
      }
    }
  }
}
