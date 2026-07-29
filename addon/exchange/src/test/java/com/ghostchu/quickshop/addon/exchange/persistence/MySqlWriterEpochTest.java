package com.ghostchu.quickshop.addon.exchange.persistence;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlWriterEpochTest {
  @Test
  void activatesANewEpochOnTheAdvisoryLockConnectionAndFencesTransactions() throws Exception {
    List<String> activationSql = new ArrayList<>();
    AtomicBoolean committed = new AtomicBoolean();
    Connection lockConnection = connection(activationSql, committed, 7L, true, true);

    TransactionFence fence = MySqlWriterEpoch.activate(
        lockConnection, "qs_exchange_writer", new TableNames("qs_"));

    assertThat(committed).isTrue();
    assertThat(activationSql).anyMatch(sql -> sql.contains("IS_USED_LOCK"));
    assertThat(activationSql).anyMatch(sql -> sql.contains("FOR UPDATE"));
    assertThat(activationSql).anyMatch(sql -> sql.startsWith("UPDATE qs_exchange_writer_epoch"));

    List<String> transactionSql = new ArrayList<>();
    fence.acquire(connection(transactionSql, new AtomicBoolean(), 8L, true, true));
    assertThat(transactionSql).singleElement().asString().contains("LOCK IN SHARE MODE");
  }

  @Test
  void rejectsActivationWhenTheConnectionDoesNotOwnTheAdvisoryLock() {
    Connection connection = connection(new ArrayList<>(), new AtomicBoolean(), 7L, false, true);

    assertThatThrownBy(() -> MySqlWriterEpoch.activate(
        connection, "qs_exchange_writer", new TableNames("qs_")))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("advisory lock");
  }

  @Test
  void rejectsAStaleEpochInsideTheBusinessTransaction() throws Exception {
    TransactionFence fence = MySqlWriterEpoch.forEpoch(new TableNames("qs_"), 8L);

    assertThatThrownBy(() -> fence.acquire(
        connection(new ArrayList<>(), new AtomicBoolean(), 9L, true, false)))
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("stale exchange writer epoch");
  }

  private static Connection connection(
      List<String> sql, AtomicBoolean committed, long currentEpoch,
      boolean ownsLock, boolean fenceMatches) {
    AtomicBoolean autoCommit = new AtomicBoolean(true);
    return (Connection) Proxy.newProxyInstance(
        MySqlWriterEpochTest.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "getAutoCommit" -> autoCommit.get();
          case "setAutoCommit" -> {
            autoCommit.set((Boolean) arguments[0]);
            yield null;
          }
          case "prepareStatement" -> {
            String statementSql = (String) arguments[0];
            sql.add(statementSql);
            yield statement(statementSql, currentEpoch, ownsLock, fenceMatches);
          }
          case "commit" -> {
            committed.set(true);
            yield null;
          }
          case "rollback", "close" -> null;
          case "isClosed" -> false;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static PreparedStatement statement(
      String sql, long currentEpoch, boolean ownsLock, boolean fenceMatches) {
    AtomicInteger updates = new AtomicInteger();
    return (PreparedStatement) Proxy.newProxyInstance(
        MySqlWriterEpochTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "setString", "setLong", "close" -> null;
          case "executeQuery" -> resultSet(sql, currentEpoch, ownsLock, fenceMatches);
          case "executeUpdate" -> updates.incrementAndGet();
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static ResultSet resultSet(
      String sql, long currentEpoch, boolean ownsLock, boolean fenceMatches) {
    AtomicBoolean first = new AtomicBoolean(true);
    boolean hasRow = sql.contains("IS_USED_LOCK") ? ownsLock
        : sql.contains("LOCK IN SHARE MODE") ? fenceMatches : true;
    return (ResultSet) Proxy.newProxyInstance(
        MySqlWriterEpochTest.class.getClassLoader(), new Class<?>[] {ResultSet.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "next" -> hasRow && first.getAndSet(false);
          case "getBoolean" -> ownsLock;
          case "getLong" -> currentEpoch;
          case "close" -> null;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
