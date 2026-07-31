package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeMarketDisplayDataSourceTest {
  @Test
  void loadsQuoteAndCandlesForTheRequestedHalfOpenPeriod() throws Exception {
    Instant now = Instant.parse("2026-07-30T12:00:00Z");
    MarketQuote quote = quote(now);
    Candle candle = candle(now.minusSeconds(60));
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "钻石市场", () -> quote,
              (fromInclusive, toExclusive) -> {
                assertThat(fromInclusive).isEqualTo(now.minusSeconds(6 * 60 * 60));
                assertThat(toExclusive).isEqualTo(now);
                return List.of(candle);
              })), executor);

      MarketDisplaySnapshot snapshot = source.snapshot(
          "diamond", MarketChartPeriod.SIX_HOURS, now).get(5, TimeUnit.SECONDS);

      assertThat(snapshot.marketId()).isEqualTo("diamond");
      assertThat(snapshot.displayName()).isEqualTo("钻石市场");
      assertThat(snapshot.quote()).isEqualTo(quote);
      assertThat(snapshot.candles()).containsExactly(candle);
      assertThat(snapshot.fromInclusive()).isEqualTo(now.minusSeconds(6 * 60 * 60));
      assertThat(snapshot.toExclusive()).isEqualTo(now);
    }
  }

  @Test
  void mergesPersistedAndLiveCandlesWithLiveMinuteTakingPrecedence() throws Exception {
    Instant now = Instant.parse("2026-07-30T12:00:00Z");
    Instant earlier = now.minusSeconds(120);
    Instant current = now.minusSeconds(60);
    Candle persistedEarlier = candle(earlier, "100", 2);
    Candle persistedCurrent = candle(current, "101", 4);
    Candle liveCurrent = candle(current, "105", 7);
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "钻石市场", () -> quote(now),
              (from, to) -> List.of(persistedCurrent, persistedEarlier),
              (from, to) -> List.of(liveCurrent))), executor);

      MarketDisplaySnapshot snapshot = source.snapshot(
          "diamond", MarketChartPeriod.ONE_HOUR, now).get(5, TimeUnit.SECONDS);

      assertThat(snapshot.candles()).containsExactly(persistedEarlier, liveCurrent);
      assertThat(snapshot.candles()).extracting(Candle::volume).containsExactly(2L, 7L);
    }
  }

  @Test
  void returnsImmediatelyWhileBackgroundReadIsStillBlocked() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Instant now = Instant.parse("2026-07-30T12:00:00Z");
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "钻石市场", () -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("test did not release quote read");
                }
                return quote(now);
              }, (from, to) -> List.of())), executor);

      var future = source.snapshot("diamond", MarketChartPeriod.ONE_DAY, now);

      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(future).isNotDone();
      release.countDown();
      assertThat(future.get(5, TimeUnit.SECONDS).quote()).isEqualTo(quote(now));
    }
  }

  @Test
  void propagatesRepositoryFailureThroughTheFuture() {
    Instant now = Instant.parse("2026-07-30T12:00:00Z");
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(
          Map.of("diamond", new ExchangeMarketDisplayDataSource.MarketAccess(
              "钻石市场", () -> quote(now),
              (from, to) -> { throw new java.sql.SQLException("database unavailable"); })),
          executor);

      assertThatThrownBy(() -> source.snapshot(
          "diamond", MarketChartPeriod.ONE_DAY, now).join())
          .isInstanceOf(CompletionException.class)
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }
  }

  @Test
  void rejectsUnknownMarketBeforeSchedulingWork() {
    try (var executor = Executors.newSingleThreadExecutor()) {
      ExchangeMarketDisplayDataSource source = new ExchangeMarketDisplayDataSource(Map.of(), executor);

      assertThatThrownBy(() -> source.snapshot("missing", MarketChartPeriod.ONE_DAY, Instant.now()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static MarketQuote quote(Instant now) {
    return new MarketQuote("diamond", new BigDecimal("105"), new BigDecimal("100"),
        new BigDecimal("104"), new BigDecimal("106"), new BigDecimal("0.05"),
        20, new BigDecimal("2100"), MarketStatus.OPEN, now);
  }

  private static Candle candle(Instant at) {
    return candle(at, "105", 20);
  }

  private static Candle candle(Instant at, String close, long volume) {
    BigDecimal price = new BigDecimal(close);
    return new Candle("diamond", at, price, price, price, price, volume,
        price.multiply(BigDecimal.valueOf(volume)));
  }
}
