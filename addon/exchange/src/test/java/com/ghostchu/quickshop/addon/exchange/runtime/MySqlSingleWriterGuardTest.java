package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.persistence.TransactionFence;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlSingleWriterGuardTest {
  @Test
  void fencesTheRuntimeWhenItsDedicatedLockConnectionIsLost() throws Exception {
    AtomicBoolean valid = new AtomicBoolean(true);
    AtomicBoolean fenced = new AtomicBoolean();
    MySqlSingleWriterGuard guard = new MySqlSingleWriterGuard(
        () -> lockConnection(valid), "qs_");
    guard.onLockLost(() -> fenced.set(true));

    guard.acquire();
    valid.set(false);

    long deadline = System.nanoTime() + 2_000_000_000L;
    while (!fenced.get() && System.nanoTime() < deadline) {
      Thread.sleep(10L);
    }
    assertThat(fenced).isTrue();
    assertThat(guard.held()).isFalse();
    guard.close();
  }

  @Test
  void activatesTheDatabaseEpochOnItsDedicatedLockConnection() throws Exception {
    AtomicBoolean valid = new AtomicBoolean(true);
    MySqlSingleWriterGuard guard = new MySqlSingleWriterGuard(
        () -> lockConnection(valid), "qs_", Duration.ofSeconds(1));
    guard.acquire();

    TransactionFence fence = guard.activateTransactionFence(new TableNames("qs_"));

    assertThat(fence).isNotNull();
    guard.close();
  }

  @Test
  void waitsForGuardedWorkBeforePublishingLockLoss() throws Exception {
    AtomicBoolean valid = new AtomicBoolean(true);
    AtomicBoolean fenced = new AtomicBoolean();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    MySqlSingleWriterGuard guard = new MySqlSingleWriterGuard(
        () -> lockConnection(valid), "qs_", Duration.ofMillis(5));
    guard.onLockLost(() -> fenced.set(true));
    guard.acquire();

    Thread guarded = Thread.ofPlatform().start(() -> {
      try {
        guard.runWhileHeld(() -> {
          entered.countDown();
          release.await();
        });
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    });
    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    valid.set(false);
    Thread.sleep(30L);
    assertThat(fenced).isFalse();

    release.countDown();
    guarded.join(1_000L);
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (!fenced.get() && System.nanoTime() < deadline) {
      Thread.sleep(5L);
    }
    assertThat(fenced).isTrue();
    guard.close();
  }

  private static Connection lockConnection(AtomicBoolean valid) {
    return (Connection) Proxy.newProxyInstance(
        MySqlSingleWriterGuardTest.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "prepareStatement" -> statement();
          case "getAutoCommit" -> true;
          case "setAutoCommit", "commit", "rollback" -> null;
          case "isValid" -> valid.get();
          case "isClosed" -> !valid.get();
          case "close" -> null;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static PreparedStatement statement() {
    return (PreparedStatement) Proxy.newProxyInstance(
        MySqlSingleWriterGuardTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "setString", "setLong", "close" -> null;
          case "execute" -> true;
          case "executeQuery" -> resultSet();
          case "executeUpdate" -> 1;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static ResultSet resultSet() {
    AtomicBoolean first = new AtomicBoolean(true);
    return (ResultSet) Proxy.newProxyInstance(
        MySqlSingleWriterGuardTest.class.getClassLoader(), new Class<?>[] {ResultSet.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "next" -> first.getAndSet(false);
          case "getBoolean" -> true;
          case "getInt" -> 1;
          case "getLong" -> 0L;
          case "close" -> null;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
