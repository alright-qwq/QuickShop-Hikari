# Exchange Adaptive K-Line V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dense one-minute stock-style chart with a readable Minecraft low-liquidity chart that preserves real candles, shows gaps honestly, and overlays the trusted reference price.

**Architecture:** Keep raw minute Candles as the durable source, aggregate only buckets that contain real trades, and select a 5m/15m/1h/6h/24h interval according to the number of effective candles and map dimensions. Build a renderer-neutral series containing raw candles, trusted reference points, gaps, and summary metadata, then render size-specific `1x1`, `2x1`, and `2x2` layouts without AWT.

**Tech Stack:** Java 21, existing pure byte-pixel renderer, BigDecimal, UTC time buckets, Bukkit map palette, JUnit 5, AssertJ

---

## File map

- `display/MarketChartInterval.java`: supported sparse aggregation intervals.
- `display/AdaptiveChartIntervalSelector.java`: choose interval for density/size.
- `display/SparseCandleAggregator.java`: aggregate real candles without synthesizing buckets.
- `display/TrustedPricePoint.java`: reference line sample.
- `display/MarketChartSeries.java`: raw candles, trusted line, gaps, range and summary.
- `display/MarketChartSeriesBuilder.java`: construct v2 series.
- `display/MarketChartLayout.java`: size-specific information hierarchy.
- `display/MarketChartRenderer.java`: candles, line, gaps and special cases.
- `display/ExchangeMarketDisplayDataSource.java`: query raw candles plus trusted points.

### Task 1: Sparse intervals and aggregation

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartInterval.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/SparseCandleAggregator.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/SparseCandleAggregatorTest.java`

- [ ] **Step 1: Write failing sparse-bucket tests**

```java
@Test
void aggregatesOnlyBucketsContainingTrades() {
  List<Candle> result = aggregator.aggregate(List.of(
      candle("2026-07-31T00:01:00Z", "100", "101", "99", "100", 2),
      candle("2026-07-31T00:04:00Z", "100", "103", "100", "102", 3),
      candle("2026-07-31T00:16:00Z", "110", "110", "108", "109", 1)),
      MarketChartInterval.FIVE_MINUTES);
  assertThat(result).hasSize(2);
  assertThat(result.getFirst().open()).isEqualByComparingTo("100");
  assertThat(result.getFirst().close()).isEqualByComparingTo("102");
  assertThat(result.getFirst().volume()).isEqualTo(5);
  assertThat(result.getLast().bucketStart()).isEqualTo(Instant.parse("2026-07-31T00:15:00Z"));
}
```

Also test exact boundary timestamps, unsorted input, duplicate minute rejection/merge policy, high/low, notional sum, overflow, and a 24-hour UTC boundary.

- [ ] **Step 2: Verify RED and implement intervals**

```java
public enum MarketChartInterval {
  FIVE_MINUTES("5m", Duration.ofMinutes(5)),
  FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
  ONE_HOUR("1h", Duration.ofHours(1)),
  SIX_HOURS("6h", Duration.ofHours(6)),
  ONE_DAY("24h", Duration.ofDays(1));
}

long bucketMillis = interval.duration().toMillis();
Instant bucket = Instant.ofEpochMilli(Math.floorDiv(
    candle.bucketStart().toEpochMilli(), bucketMillis) * bucketMillis);
```

Group only source entries. Sort each group; open/close come from first/last, high/low are extrema, volume uses `Math.addExact`, notional uses `BigDecimal.add`. Never insert an absent bucket or draw fake candles.

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartInterval.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/SparseCandleAggregator.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/SparseCandleAggregatorTest.java
git commit -m "feat(exchange): aggregate sparse market candles"
```

### Task 2: Adaptive interval selection and gap model

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/AdaptiveChartIntervalSelector.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ChartGap.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/AdaptiveChartIntervalSelectorTest.java`

- [ ] **Step 1: Write failing size-density tests**

```java
@ParameterizedTest
@CsvSource({"1,1,12", "2,1,24", "2,2,48"})
void selectsSmallestIntervalWithinTarget(int columns, int rows, int target) {
  Selection selection = selector.select(sourceCandles, dimensions(columns, rows), range);
  assertThat(selection.candles().size()).isLessThanOrEqualTo(target);
  assertThat(selection.interval()).isEqualTo(expectedSmallestInterval(sourceCandles, target));
}
```

Test zero candles, one candle, 12/13, 24/25, 48/49 boundaries, and a long gap between two active buckets.

- [ ] **Step 2: Verify RED and implement deterministic selection**

For each interval from 5m to 24h, aggregate and choose the first result whose effective count is at most the dimension target (`12`, `24`, `48`). If even 24h exceeds the target, downsample contiguous active entries only after aggregation while preserving first/last and OHLCV. Produce `ChartGap` whenever adjacent buckets differ by more than one selected interval; do not create empty candles.

```java
public record Selection(MarketChartInterval interval, List<Candle> candles,
                        List<ChartGap> gaps) { }
```

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/AdaptiveChartIntervalSelector.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ChartGap.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/AdaptiveChartIntervalSelectorTest.java
git commit -m "feat(exchange): select adaptive chart intervals"
```

### Task 3: Raw candle plus trusted reference series

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/TrustedPricePoint.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeries.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilder.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilderV2Test.java`

- [ ] **Step 1: Write failing dual-series tests**

```java
@Test
void preservesRawExtremesAndAddsTrustedLine() {
  MarketChartSeries result = builder.build(rawCandles, trustedPoints,
      dimensions(2, 1), period);
  assertThat(result.candles()).extracting(ChartCandle::high)
      .contains(new BigDecimal("115.00"));
  assertThat(result.trustedPoints()).extracting(TrustedPricePoint::price)
      .containsExactly(bd("100.50"), bd("100.24875"));
  assertThat(result.latestRawPrice()).isEqualByComparingTo("85.00");
  assertThat(result.latestTrustedPrice()).isEqualByComparingTo("100.24875");
}
```

Test that a long no-trade period produces a gap marker, not a synthetic Candle; a maintenance reversion can add a trusted point without volume; and duplicate live/persisted minutes use live once.

- [ ] **Step 2: Implement explicit v2 series fields**

```java
public record MarketChartSeries(
    MarketChartInterval interval, List<ChartCandle> candles,
    List<TrustedPricePoint> trustedPoints, List<ChartGap> gaps,
    BigDecimal minimum, BigDecimal maximum,
    BigDecimal latestRawPrice, BigDecimal latestTrustedPrice,
    LiquidityTier liquidityTier, boolean flat, boolean singleCandle) { }
```

The min/max range includes raw high/low and trusted points. A flat range receives `max(one tick, abs(price) * 0.01)` padding. Sort points by time and retain a maintenance/admin point as reference data, never as Candle volume.

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilderV2Test.java
git commit -m "feat(exchange): build trusted market chart series"
```

### Task 4: Size-specific layout and special cases

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayout.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartOptions.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayoutV2Test.java`

- [ ] **Step 1: Write failing region and information-hierarchy tests**

Assert every rectangle is inside the pixel canvas and non-overlapping for `1x1`, `2x1`, and `2x2`. For `1x1`, price/time axes and full volume panel are absent; for `2x1`, plot/price axis/compact volume exist; for `2x2`, header, plot, price axis, volume, time axis, legend, and confidence region exist.

- [ ] **Step 2: Implement optional regions instead of zero-size rectangles**

```java
public record MarketChartLayout(
    Density density, Rect header, Rect plot, Optional<Rect> priceAxis,
    Optional<Rect> volume, Optional<Rect> timeAxis,
    Optional<Rect> legend, Optional<Rect> confidence) { }
```

Add `showTrustedPriceLine` and `showGapMarkers` to `MarketChartOptions`; defaults are true. Keep volume/latest labels conditional on both option and available region.

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayout.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartOptions.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayoutV2Test.java
git commit -m "feat(exchange): redesign market chart layouts"
```

### Task 5: V2 byte-pixel renderer

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartPalette.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRendererV2Test.java`

- [ ] **Step 1: Write failing semantic pixel tests**

Render fixtures for empty, one candle, flat, sparse gaps, extreme scientific notation, raw spike with stable trusted line, and all three dimensions. Assert exact canvas size; background/header remain intact; raw red/green/gray candle colors appear; trusted color appears only when points exist; gap markers appear only at gaps; one-candle view draws a range/summary rather than a misleading thin timeline.

- [ ] **Step 2: Implement ordered render passes**

```text
background -> header/summary -> soft grid -> gaps -> raw candles/line
           -> trusted reference line -> latest trusted label -> raw-last secondary label
           -> volume -> axes -> legend/confidence
```

Map candle x positions across effective entries with variable body width `max(1, floor(plotWidth / count) - gap)`. Reserve visible gap width without inserting a candle. Clamp all drawing through existing pixel helpers. Render trusted reference with a dedicated palette entry and legend. `1x1` renders summary plus simplified plot; `2x1` adds axes/volume; `2x2` adds confidence and full legend.

- [ ] **Step 3: Produce review artifacts**

Use the test/debug renderer to write representative `1x1`, `2x1`, and `2x2` PNG/PPM images under `outputs/chart-v2-review/` without committing generated binaries. Inspect them for label collisions, clipped prices, indistinguishable lines, and unreadable candles.

- [ ] **Step 4: Run renderer tests GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRendererV2Test.java
git commit -m "feat(exchange): render adaptive trusted K-lines"
```

### Task 6: Trusted data source, configuration, and display integration

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSource.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplaySnapshot.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartCache.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeRepository.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSourceV2Test.java`

- [ ] **Step 1: Write failing query/merge tests**

Query a range containing persisted raw Candles, a live Candle in the same minute, trade influences, anchor reversion, and admin reanchor. Assert live overrides persisted once; raw volume is unchanged by trusted adjustments; trusted points are ordered/deduplicated; liquidity tier and raw/trusted latest prices reach the series.

- [ ] **Step 2: Add bounded trusted-point repository read**

```java
List<TrustedPricePoint> loadTrustedPricePoints(
    String marketId, Instant fromInclusive, Instant toExclusive) throws SQLException;
```

Read influence `reference_after` plus adjustment `reference_after` using a union ordered by time and stable sequence/id. Do not query per map tile; one display snapshot feeds all tiles of a wall and is cacheable by market/range/version.

Extend `MarketDisplaySnapshot` with trusted points and liquidity tier. Include the trusted state version, selected interval, and chart-option version in its fingerprint/cache key so a guidance reanchor or hot display change cannot reuse stale pixels.

- [ ] **Step 3: Wire adaptive builder and hot display options**

Parse `show-trusted-price-line`, `show-gap-markers`, and optional fixed interval (`auto` default). Existing `KLINE` uses v2; `LINE` may retain raw close line but still shows trusted overlay. Admin display changes invalidate the display snapshot cache and refresh asynchronously.

- [ ] **Step 4: Run targeted/full verification**

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest='*Chart*V2Test,SparseCandleAggregatorTest,AdaptiveChartIntervalSelectorTest,ExchangeMarketDisplayDataSourceV2Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 verify
```

Expected: all chart and reactor tests pass.

- [ ] **Step 5: Real-server visual and performance acceptance**

On Paper and Folia/QSH 6.2.0.11, bind `1x1`, `2x1`, and `2x2` walls for an empty, flat, sparse, and active market. Confirm orientation, no fake candles, trusted/raw distinction, readable labels, no forced chunk loads, bounded DB queries, and owner-thread-safe map updates.

- [ ] **Step 6: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/main/resources addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSourceV2Test.java
git commit -m "feat(exchange): publish adaptive trusted market charts"
```
