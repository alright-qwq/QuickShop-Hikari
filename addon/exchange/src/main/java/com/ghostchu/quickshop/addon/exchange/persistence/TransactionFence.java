package com.ghostchu.quickshop.addon.exchange.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** Validates writer ownership inside the database transaction before domain work executes. */
@FunctionalInterface
public interface TransactionFence {
  TransactionFence NONE = connection -> {};

  void acquire(Connection connection) throws SQLException;
}
