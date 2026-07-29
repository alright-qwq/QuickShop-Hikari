package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

/** Durable writer generation used to fence stale MySQL mutation transactions. */
public final class SchemaV4 {
  private SchemaV4() {}

  public static List<String> statements(SqlDialect dialect, TableNames tables) {
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + tables.writerEpoch()
            + " (writer_id VARCHAR(32) PRIMARY KEY, epoch " + dialect.longType()
            + " NOT NULL CHECK (epoch >= 0))",
        "INSERT INTO " + tables.writerEpoch()
            + " (writer_id,epoch) SELECT 'exchange',0 WHERE NOT EXISTS (SELECT 1 FROM "
            + tables.writerEpoch() + " WHERE writer_id='exchange')");
  }
}
