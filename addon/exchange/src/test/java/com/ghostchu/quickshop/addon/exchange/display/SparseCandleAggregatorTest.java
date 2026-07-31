package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparseCandleAggregatorTest {
  private final SparseCandleAggregator aggregator = new SparseCandleAggregator();

  @Test
  void aggregatesOnlyBucketsContainingTrades() {
    List<Candle> result = aggregator.aggregate(List.of(
        candle("2026-07-31T00:01:00Z", "100", "101", "99", "100", 2, "200"),
        candle("2026-07-31T00:04:00Z", "100", "103", "100", "102", 3, "306"),
        candle("2026-07-31T00:16:00Z", "110", "110", "108", "109", 1, "109")),
        MarketChartInterval.FIVE_MINUTES);

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().bucketStart()).isEqualTo(Instant.parse("2026-07-31T00:00:00Z"));
    assertThat(result.getFirst().open()).isEqualByComparingTo("100");
    assertThat(result.getFirst().high()).isEqualByComparingTo("103");
    assertThat(result.getFirst().low()).isEqualByComparingTo("99");
    assertThat(result.getFirst().close()).isEqualByComparingTo("102");
    assertThat(result.getFirst().volume()).isEqualTo(5);
    assertThat(result.getFirst().notional()).isEqualByComparingTo("506");
    assertThat(result.getLast().bucketStart()).isEqualTo(Instant.parse("2026-07-31T00:15:00Z"));
  }

  @Test
  void assignsAnExactIntervalBoundaryToTheFollowingBucket() {
    List<Candle> result = aggregator.aggregate(List.of(
        candle("2026-07-31T00:04:00Z", "10", "10", "10", "10", 1, "10"),
        candle("2026-07-31T00:05:00Z", "11", "11", "11", "11", 1, "11")),
        MarketChartInterval.FIVE_MINUTES);

    assertThat(result).extracting(Candle::bucketStart).containsExactly(
        Instant.parse("2026-07-31T00:00:00Z"), Instant.parse("2026-07-31T00:05:00Z"));
  }

  @Test
  void sortsSourceCandlesBeforeComputingOpenAndClose() {
    List<Candle> result = aggregator.aggregate(List.of(
        candle("2026-07-31T00:09:00Z", "30", "31", "29", "30", 1, "30"),
        candle("2026-07-31T00:15:00Z", "40", "40", "40", "40", 1, "40"),
        candle("2026-07-31T00:05:00Z", "20", "21", "19", "21", 1, "21")),
        MarketChartInterval.FIVE_MINUTES);

    assertThat(result).extracting(Candle::bucketStart).containsExactly(
        Instant.parse("2026-07-31T00:05:00Z"), Instant.parse("2026-07-31T00:15:00Z"));
    assertThat(result.getFirst().open()).isEqualByComparingTo("20");
    assertThat(result.getFirst().close()).isEqualByComparingTo("30");
  }

  @Test
  void rejectsDuplicateSourceMinutesToAvoidDoubleCounting() {
    List<Candle> source = List.of(
        candle("2026-07-31T00:01:00Z", "10", "10", "10", "10", 1, "10"),
        candle("2026-07-31T00:01:00Z", "11", "11", "11", "11", 1, "11"));

    assertThatThrownBy(() -> aggregator.aggregate(source, MarketChartInterval.FIVE_MINUTES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void rejectsVolumeOverflowRatherThanWrapping() {
    List<Candle> source = List.of(
        candle("2026-07-31T00:01:00Z", "10", "10", "10", "10", Long.MAX_VALUE, "10"),
        candle("2026-07-31T00:02:00Z", "10", "10", "10", "10", 1, "10"));

    assertThatThrownBy(() -> aggregator.aggregate(source, MarketChartInterval.FIVE_MINUTES))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void alignsOneDayBucketsAtUtcMidnight() {
    List<Candle> result = aggregator.aggregate(List.of(
        candle("2026-07-31T23:59:00Z", "10", "10", "10", "10", 1, "10"),
        candle("2026-08-01T00:00:00Z", "11", "11", "11", "11", 1, "11")),
        MarketChartInterval.ONE_DAY);

    assertThat(result).extracting(Candle::bucketStart).containsExactly(
        Instant.parse("2026-07-31T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
  }

  @Test
  void exposesTheSupportedIntervals() {
    assertThat(MarketChartInterval.FIVE_MINUTES.duration()).isEqualTo(Duration.ofMinutes(5));
    assertThat(MarketChartInterval.FIFTEEN_MINUTES.duration()).isEqualTo(Duration.ofMinutes(15));
    assertThat(MarketChartInterval.ONE_HOUR.duration()).isEqualTo(Duration.ofHours(1));
    assertThat(MarketChartInterval.SIX_HOURS.duration()).isEqualTo(Duration.ofHours(6));
    assertThat(MarketChartInterval.ONE_DAY.duration()).isEqualTo(Duration.ofDays(1));
  }

  private static Candle candle(String at, String open, String high, String low, String close,
                               long volume, String notional) {
    return new Candle("minecraft_diamond/default", Instant.parse(at), new BigDecimal(open),
        new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), volume,
        new BigDecimal(notional));
  }
}
