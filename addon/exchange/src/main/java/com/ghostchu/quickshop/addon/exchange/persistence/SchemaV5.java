package com.ghostchu.quickshop.addon.exchange.persistence;

import java.util.List;

/** Durable trusted-price state and immutable influence/adjustment events. */
public final class SchemaV5 {
  private SchemaV5() {}

  public static List<String> statements(SqlDialect dialect, TableNames tables) {
    String amount = dialect.decimalType();
    String id = dialect.uuidType();
    String number = dialect.longType();
    return List.of(
        "CREATE TABLE IF NOT EXISTS " + tables.trustedMarketState()
            + " (market_id VARCHAR(128) PRIMARY KEY, trusted_price " + amount
            + " NOT NULL CHECK (" + positive(dialect, "trusted_price") + "),"
            + " guidance_price " + amount + " NOT NULL CHECK ("
            + positive(dialect, "guidance_price") + "), last_evaluated_at " + number
            + " NOT NULL, confidence_tier VARCHAR(16) NOT NULL, policy_version " + number
            + " NOT NULL CHECK (policy_version > 0), last_match_sequence " + number
            + " NOT NULL CHECK (last_match_sequence >= 0), state_version " + number
            + " NOT NULL CHECK (state_version >= 0), FOREIGN KEY (market_id) REFERENCES "
            + tables.markets() + "(market_id))",
        "CREATE TABLE IF NOT EXISTS " + tables.trustedMarketInfluence()
            + " (trade_id " + id + " PRIMARY KEY, market_id VARCHAR(128) NOT NULL,"
            + " match_sequence " + number + " NOT NULL CHECK (match_sequence >= 0),"
            + " buyer_account_id " + id + " NOT NULL, seller_account_id " + id
            + " NOT NULL, pair_key VARCHAR(73) NOT NULL, trade_price " + amount
            + " NOT NULL CHECK (" + positive(dialect, "trade_price") + "), quantity "
            + number + " NOT NULL CHECK (quantity >= 0), reference_before " + amount
            + " NOT NULL CHECK (" + positive(dialect, "reference_before") + "),"
            + " reference_after " + amount + " NOT NULL CHECK ("
            + positive(dialect, "reference_after") + "), requested_move " + amount
            + " NOT NULL CHECK (" + nonNegative(dialect, "requested_move") + "),"
            + " accepted_move " + amount + " NOT NULL CHECK ("
            + nonNegative(dialect, "accepted_move") + "), quantity_factor " + amount
            + " NOT NULL CHECK (" + nonNegative(dialect, "quantity_factor") + "),"
            + " confidence_tier VARCHAR(16) NOT NULL, policy_version " + number
            + " NOT NULL CHECK (policy_version > 0), limit_reasons TEXT NOT NULL,"
            + " executed_at " + number + " NOT NULL, FOREIGN KEY (market_id) REFERENCES "
            + tables.markets() + "(market_id), FOREIGN KEY (trade_id) REFERENCES "
            + tables.trades() + "(trade_id))",
        "CREATE TABLE IF NOT EXISTS " + tables.trustedMarketAdjustment()
            + " (adjustment_id " + id + " PRIMARY KEY, market_id VARCHAR(128) NOT NULL,"
            + " adjustment_type VARCHAR(32) NOT NULL, trusted_price_before " + amount
            + " NOT NULL CHECK (" + positive(dialect, "trusted_price_before") + "),"
            + " trusted_price_after " + amount + " NOT NULL CHECK ("
            + positive(dialect, "trusted_price_after") + "), guidance_price_before "
            + amount + " NOT NULL CHECK (" + positive(dialect, "guidance_price_before")
            + "), guidance_price_after " + amount + " NOT NULL CHECK ("
            + positive(dialect, "guidance_price_after") + "), actor_id " + id
            + ", reason TEXT NOT NULL, policy_version " + number
            + " NOT NULL CHECK (policy_version > 0), adjusted_at " + number
            + " NOT NULL, FOREIGN KEY (market_id) REFERENCES " + tables.markets()
            + "(market_id))");
  }

  public static List<SchemaV1.IndexDefinition> indexes(TableNames tables) {
    String prefix = tables.prefix() + "exchange_trusted_";
    return List.of(
        new SchemaV1.IndexDefinition(prefix + "influence_market_time_idx",
            tables.trustedMarketInfluence(), "market_id,executed_at"),
        new SchemaV1.IndexDefinition(prefix + "influence_buyer_time_idx",
            tables.trustedMarketInfluence(), "market_id,buyer_account_id,executed_at"),
        new SchemaV1.IndexDefinition(prefix + "influence_seller_time_idx",
            tables.trustedMarketInfluence(), "market_id,seller_account_id,executed_at"),
        new SchemaV1.IndexDefinition(prefix + "influence_pair_time_idx",
            tables.trustedMarketInfluence(), "market_id,pair_key,executed_at"),
        new SchemaV1.IndexDefinition(prefix + "adjustment_market_time_idx",
            tables.trustedMarketAdjustment(), "market_id,adjusted_at"));
  }

  private static String positive(SqlDialect dialect, String column) {
    return comparison(dialect, column, "> 0");
  }

  private static String nonNegative(SqlDialect dialect, String column) {
    return comparison(dialect, column, ">= 0");
  }

  private static String comparison(SqlDialect dialect, String column, String condition) {
    return dialect == SqlDialect.SQLITE
        ? "CAST(" + column + " AS NUMERIC) " + condition
        : column + " " + condition;
  }
}
