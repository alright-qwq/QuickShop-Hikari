package com.ghostchu.quickshop.addon.exchange.runtime;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

  private static Connection lockConnection(AtomicBoolean valid) {
    return (Connection) Proxy.newProxyInstance(
        MySqlSingleWriterGuardTest.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, arguments) -> switch (method.getName()) {
          case "prepareStatement" -> statement();
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
          case "setString", "close", "execute" -> null;
          case "executeQuery" -> resultSet();
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
          case "getInt" -> 1;
          case "close" -> null;
          case "unwrap" -> null;
          case "isWrapperFor" -> false;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
