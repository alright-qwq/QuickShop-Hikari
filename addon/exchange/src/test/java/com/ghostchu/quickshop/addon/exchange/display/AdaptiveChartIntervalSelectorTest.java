package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveChartIntervalSelectorTest {
  private final AdaptiveChartIntervalSelector selector = new AdaptiveChartIntervalSelector();

  @ParameterizedTest
  @CsvSource({
      "1, 1, 12, FIVE_MINUTES",
      "1, 1, 13, FIFTEEN_MINUTES",
      "2, 1, 24, FIVE_MINUTES",
      "2, 1, 25, FIFTEEN_MINUTES",
      "2, 2, 48, FIVE_MINUTES",
      "2, 2, 49, FIFTEEN_MINUTES"
  })
  void selectsTheSmallestIntervalWhoseActiveCandleCountFitsTheChart(
      int columns, int rows, int sourceCount, MarketChartInterval expectedInterval) {
    AdaptiveChartIntervalSelector.Selection selection = selector.select(
        consecutiveFiveMinuteCandles(sourceCount), new MarketChartDimensions(columns, rows));

    assertThat(selection.candles()).hasSizeLessThanOrEqualTo(columns * rows * 12);
    assertThat(selection.interval()).isEqualTo(expectedInterval);
  }

  @Test
  void selectsFiveMinutesForNoCandles() {
    AdaptiveChartIntervalSelector.Selection selection = selector.select(List.of(),
        new MarketChartDimensions(1, 1));

    assertThat(selection.interval()).isEqualTo(MarketChartInterval.FIVE_MINUTES);
    assertThat(selection.candles()).isEmpty();
    assertThat(selection.gaps()).isEmpty();
  }

  @Test
  void selectsFiveMinutesForOneCandle() {
    Candle candle = candleAt("2026-07-31T00:00:00Z", 1);

    AdaptiveChartIntervalSelector.Selection selection = selector.select(List.of(candle),
        new MarketChartDimensions(1, 1));

    assertThat(selection.interval()).isEqualTo(MarketChartInterval.FIVE_MINUTES);
    assertThat(selection.candles()).containsExactly(candle);
    assertThat(selection.gaps()).isEmpty();
  }

  @Test
  void representsInactiveTimeAsAGapWithoutCreatingEmptyCandles() {
    Candle first = candleAt("2026-07-31T00:00:00Z", 1);
    Candle second = candleAt("2026-07-31T01:00:00Z", 2);

    AdaptiveChartIntervalSelector.Selection selection = selector.select(List.of(first, second),
        new MarketChartDimensions(1, 1));

    assertThat(selection.interval()).isEqualTo(MarketChartInterval.FIVE_MINUTES);
    assertThat(selection.candles()).containsExactly(first, second);
    assertThat(selection.gaps()).containsExactly(new ChartGap(first.bucketStart(), second.bucketStart()));
  }

  @Test
  void downsamplesTwentyFourHourFallbackWithoutMergingAcrossInactiveTime() {
    List<Candle> source = IntStream.range(0, 49)
        .mapToObj(index -> candleAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index * 86_400L),
            index + 1))
        .toList();

    AdaptiveChartIntervalSelector.Selection selection = selector.select(source,
        new MarketChartDimensions(1, 1));

    assertThat(selection.interval()).isEqualTo(MarketChartInterval.ONE_DAY);
    assertThat(selection.candles()).hasSizeLessThanOrEqualTo(12);
    assertThat(selection.candles().getFirst().bucketStart()).isEqualTo(source.getFirst().bucketStart());
    assertThat(selection.candles().getFirst().open()).isEqualByComparingTo("1");
    assertThat(selection.candles().getLast().close()).isEqualByComparingTo("49.5");
    assertThat(selection.candles().stream().mapToLong(Candle::volume).sum()).isEqualTo(49L);
    assertThat(selection.candles().stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1249.5");
    assertThat(selection.candles().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
        .isEqualByComparingTo("49.5");
    assertThat(selection.candles().stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow())
        .isEqualByComparingTo("1");
    assertThat(selection.gaps()).isEmpty();
  }

  @Test
  void downsamplesSparseDailyRunsToTheChartBudget() {
    List<Candle> source = new ArrayList<>();
    for (int run = 0; run < 20; run++) {
      Instant start = Instant.parse("2026-01-01T00:00:00Z")
          .plusSeconds(run * 7L * 86_400L);
      for (int day = 0; day < 6; day++) {
        source.add(candleAt(start.plusSeconds(day * 86_400L), run * 6 + day + 1));
      }
    }

    AdaptiveChartIntervalSelector.Selection selection = selector.select(source,
        new MarketChartDimensions(1, 1));

    assertThat(selection.interval()).isEqualTo(MarketChartInterval.ONE_DAY);
    assertThat(selection.candles()).hasSize(20);
    assertThat(selection.candles()).isNotEmpty();
    assertThat(selection.gaps()).hasSize(19);
    assertThat(selection.candles().stream().mapToLong(Candle::volume).sum())
        .isEqualTo(source.stream().mapToLong(Candle::volume).sum());
    assertThat(selection.candles().stream().map(Candle::high).max(BigDecimal::compareTo)
        .orElseThrow()).isEqualTo(source.stream().map(Candle::high).max(BigDecimal::compareTo)
            .orElseThrow());
  }

  private static List<Candle> consecutiveFiveMinuteCandles(int count) {
    List<Candle> candles = new ArrayList<>(count);
    Instant start = Instant.parse("2026-07-31T00:00:00Z");
    for (int index = 0; index < count; index++) {
      candles.add(candleAt(start.plusSeconds(index * 300L), index + 1));
    }
    return candles;
  }

  private static Candle candleAt(String timestamp, int value) {
    return candleAt(Instant.parse(timestamp), value);
  }

  private static Candle candleAt(Instant timestamp, int value) {
    BigDecimal open = BigDecimal.valueOf(value);
    BigDecimal close = BigDecimal.valueOf(value).add(new BigDecimal("0.5"));
    return new Candle("minecraft_diamond/default", timestamp, open, close, open, close, 1, close);
  }
}
