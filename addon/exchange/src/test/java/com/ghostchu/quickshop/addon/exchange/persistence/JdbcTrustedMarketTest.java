package com.ghostchu.quickshop.addon.exchange.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ghostchu.quickshop.addon.exchange.core.trust.AdjustmentType;
import com.ghostchu.quickshop.addon.exchange.core.trust.LimitReason;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceAdjustment;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.repository.TrustedMarketSnapshot;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcTrustedMarketTest {
  private static final String MARKET = "diamond-usd";
  private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");
  private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID TRADE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID ADJUSTMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

  @Test
  void stateInfluenceAndAdjustmentRoundTripInSequence(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    TradeInfluence influence = influence();
    TrustedPriceAdjustment adjustment = adjustment();
    TrustedPriceState nextState = nextState();

    fixture.repository.inTransaction(tx -> {
      tx.insertTradeInfluence(influence);
      tx.insertTrustedAdjustment(adjustment);
      tx.updateTrustedPriceState(nextState, 0);
      return null;
    });

    TrustedMarketSnapshot restored = fixture.repository.inTransaction(tx ->
        tx.trustedMarketSnapshot(MARKET, NOW.minusSeconds(3600), NOW.minusSeconds(86400)));
    assertThat(restored.state()).isEqualTo(nextState);
    assertThat(restored.influences()).containsExactly(influence);
    assertThat(restored.adjustments()).containsExactly(adjustment);
  }

  @Test
  void duplicateTradeInfluenceIsRejected(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    fixture.repository.inTransaction(tx -> {
      tx.insertTradeInfluence(influence());
      return null;
    });

    assertThatThrownBy(() -> fixture.repository.inTransaction(tx -> {
      tx.insertTradeInfluence(influence());
      return null;
    })).isInstanceOf(java.sql.SQLException.class);
  }

  @Test
  void wrongStateVersionFailsOptimisticUpdate(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);

    assertThatThrownBy(() -> fixture.repository.inTransaction(tx -> {
      tx.updateTrustedPriceState(nextState(), 7);
      return null;
    })).isInstanceOf(ConcurrentModificationException.class);

    TrustedMarketSnapshot restored = fixture.repository.inTransaction(tx ->
        tx.trustedMarketSnapshot(MARKET, NOW.minusSeconds(3600), NOW.minusSeconds(86400)));
    assertThat(restored.state()).isEqualTo(initialState());
  }

  @Test
  void failedTrustedWriteRollsBackAllRowsAndState(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);

    assertThatThrownBy(() -> fixture.repository.inTransaction(tx -> {
      tx.insertTradeInfluence(influence());
      tx.insertTrustedAdjustment(adjustment());
      tx.updateTrustedPriceState(nextState(), 0);
      throw new IllegalStateException("force rollback");
    })).isInstanceOf(IllegalStateException.class);

    TrustedMarketSnapshot restored = fixture.repository.inTransaction(tx ->
        tx.trustedMarketSnapshot(MARKET, NOW.minusSeconds(3600), NOW.minusSeconds(86400)));
    assertThat(restored.state()).isEqualTo(initialState());
    assertThat(restored.influences()).isEmpty();
    assertThat(restored.adjustments()).isEmpty();
  }

  @Test
  void tradeCandleMergePreservesExactSQLiteDecimals(@TempDir Path temp) throws Exception {
    Fixture fixture = fixture(temp);
    BigDecimal first = new BigDecimal("9007199254740993.0000000001");
    BigDecimal second = new BigDecimal("9007199254740993.0000000002");

    fixture.repository.inTransaction(tx -> {
      tx.recordTradeCandle(new Candle(
          MARKET, NOW, first, first, first, first, 1L, first));
      tx.recordTradeCandle(new Candle(
          MARKET, NOW, second, second, second, second, 1L, second));
      return null;
    });

    assertThat(fixture.repository.loadCandles(MARKET, NOW, NOW.plusSeconds(60)))
        .singleElement().satisfies(candle -> {
          assertThat(candle.open()).isEqualByComparingTo(first);
          assertThat(candle.high()).isEqualByComparingTo(second);
          assertThat(candle.low()).isEqualByComparingTo(first);
          assertThat(candle.close()).isEqualByComparingTo(second);
          assertThat(candle.volume()).isEqualTo(2L);
          assertThat(candle.notional()).isEqualByComparingTo(first.add(second));
        });
  }

  private static Fixture fixture(Path temp) throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("trusted-market.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    try (Connection connection = connections.open()) {
      try (PreparedStatement market = connection.prepareStatement(
          "INSERT INTO " + tables.markets()
              + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
              + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
        market.setString(1, MARKET);
        market.setString(2, "USD");
        market.setString(3, "diamond");
        market.setString(4, "{}");
        market.setString(5, "{}");
        market.setString(6, "{}");
        market.setString(7, "{}");
        market.setLong(8, 1);
        market.setLong(9, 1);
        market.setLong(10, NOW.toEpochMilli());
        market.executeUpdate();
      }
      try (PreparedStatement state = connection.prepareStatement(
          "INSERT INTO " + tables.marketState()
              + " (market_id,status,priority_sequence,match_sequence,reference_price,version)"
              + " VALUES (?,?,?,?,?,?)")) {
        state.setString(1, MARKET);
        state.setString(2, "OPEN");
        state.setLong(3, 0);
        state.setLong(4, 1);
        state.setString(5, "100.00");
        state.setLong(6, 0);
        state.executeUpdate();
      }
      try (PreparedStatement trade = connection.prepareStatement(
          "INSERT INTO " + tables.trades()
              + " (trade_id,market_id,maker_order_id,taker_order_id,buyer_account_id,"
              + "seller_account_id,price,quantity,maker_fee,taker_fee,match_sequence,executed_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
        trade.setString(1, TRADE_ID.toString());
        trade.setString(2, MARKET);
        trade.setString(3, UUID.randomUUID().toString());
        trade.setString(4, UUID.randomUUID().toString());
        trade.setString(5, BUYER.toString());
        trade.setString(6, SELLER.toString());
        trade.setString(7, "100.50");
        trade.setLong(8, 10);
        trade.setString(9, "0.00");
        trade.setString(10, "0.00");
        trade.setLong(11, 1);
        trade.setLong(12, NOW.toEpochMilli());
        trade.executeUpdate();
      }
    }
    try (Connection connection = connections.open()) {
      try (PreparedStatement state = connection.prepareStatement(
          "INSERT INTO " + tables.trustedMarketState()
              + " (market_id,trusted_price,guidance_price,last_evaluated_at,confidence_tier,"
              + "policy_version,last_match_sequence,state_version) VALUES (?,?,?,?,?,?,?,?)")) {
        state.setString(1, MARKET);
        state.setString(2, "100.0000000000");
        state.setString(3, "100.00");
        state.setLong(4, NOW.toEpochMilli());
        state.setString(5, "LOW");
        state.setLong(6, 1);
        state.setLong(7, 1);
        state.setLong(8, 0);
        state.executeUpdate();
      }
    }
    return new Fixture(new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables));
  }

  private static TrustedPriceState initialState() {
    return new TrustedPriceState(MARKET, new BigDecimal("100.0000000000"),
        new BigDecimal("100.00"), NOW, LiquidityTier.LOW, 1, 1, 0);
  }

  private static TrustedPriceState nextState() {
    return new TrustedPriceState(MARKET, new BigDecimal("100.5000000000"),
        new BigDecimal("100.00"), NOW.plusSeconds(1), LiquidityTier.LOW, 1, 1, 1);
  }

  private static TradeInfluence influence() {
    return new TradeInfluence(TRADE_ID, MARKET, 1, BUYER, SELLER,
        TradeInfluence.pairKey(BUYER, SELLER), new BigDecimal("100.50"), 10,
        new BigDecimal("100.0000000000"), new BigDecimal("100.5000000000"),
        new BigDecimal("0.005"), new BigDecimal("0.005"), BigDecimal.ONE,
        LiquidityTier.LOW, 1, Set.of(LimitReason.LIMITED_BY_TRADE), NOW);
  }

  private static TrustedPriceAdjustment adjustment() {
    return new TrustedPriceAdjustment(ADJUSTMENT_ID, MARKET, AdjustmentType.ANCHOR_REVERSION,
        new BigDecimal("100.5000000000"), new BigDecimal("100.4000000000"),
        new BigDecimal("100.00"), new BigDecimal("100.00"), null,
        "test maintenance", 1, NOW.plusSeconds(1));
  }

  private record Fixture(JdbcExchangeRepository repository) {}
}
