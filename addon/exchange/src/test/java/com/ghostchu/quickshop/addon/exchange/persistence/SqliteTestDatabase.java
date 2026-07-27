package com.ghostchu.quickshop.addon.exchange.persistence;

import java.nio.file.Path;
import java.sql.DriverManager;

final class SqliteTestDatabase {
  private SqliteTestDatabase() {}
  static ConnectionProvider at(Path file) {
    return () -> DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
  }
}
