# Exchange Market Workbench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a data-backed Exchange market overview, order-depth display, and recent-trade chart in the existing inventory GUI.

**Architecture:** Read-only snapshot methods stay in the market-data and order-service layers; `ExchangeViewService` combines them on its existing executor. Pure presenters transform snapshots into fixed-size render rows, and pages render those rows using existing TNML item icons and Folia callbacks.

**Tech Stack:** Java 21, JUnit 5, AssertJ, QuickShop, TNML inventory menus, Folia.

## Global Constraints

- Keep all database and order-book reads off the Bukkit/Folia entity thread.
- Never expose a mutable `OrderBook` outside `PersistentOrderService`.
- Render actual values in item lore; color supplements but never replaces buy/sell or price-direction text.
- Keep the four existing order-entry actions and their permissions unchanged.
- Run Maven from the root POM with `-pl addon/exchange -am`.

---

### Task 1: Read-Only Market Data Snapshots

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataServiceTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderServiceTest.java`

**Interfaces:**
- Produces `MarketDataService.recentCandles(String, Instant, Instant): List<Candle>`.
- Produces `PersistentOrderService.marketDepth(MarketDataService, int): MarketDepth`.

- [x] **Step 1: Write failing tests**

```java
assertThat(data.recentCandles("diamond-usd", from, to))
    .extracting(Candle::bucketStart).containsExactly(firstBucket, currentBucket);
assertThat(service.marketDepth(data, 5).bids())
    .extracting(MarketDataService.DepthLevel::price).containsExactly(new BigDecimal("99"));
```

- [x] **Step 2: Run tests to verify they fail**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MarketDataServiceTest,PersistentOrderServiceTest test`

Expected: compilation failure because `recentCandles` and `marketDepth` do not exist.

- [x] **Step 3: Implement the minimal snapshot methods**

```java
public List<Candle> recentCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
  Map<Instant, Candle> candlesByBucket = new TreeMap<>();
  loadPersistedCandles(marketId, fromInclusive, toExclusive)
      .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
  candles.snapshots(marketId, fromInclusive, toExclusive)
      .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
  return List.copyOf(candlesByBucket.values());
}
```

- [x] **Step 4: Run focused tests to verify they pass**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MarketDataServiceTest,PersistentOrderServiceTest test`

Expected: PASS.

### Task 2: Pure Dashboard And Overview Presenters

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDashboardSnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketOverviewSnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDashboardPresenter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketRow.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDashboardPresenterTest.java`

**Interfaces:**
- Produces `MarketDashboardPresenter.present(MarketDashboardSnapshot): DashboardRows`.
- Produces `MarketDashboardPresenter.overview(List<MarketRow>): MarketOverviewSnapshot`.

- [x] **Step 1: Write failing tests**

```java
assertThat(presenter.present(snapshot).bids()).extracting(DepthRow::price)
    .containsExactly(new BigDecimal("101"), new BigDecimal("100"));
assertThat(presenter.present(emptySnapshot).candles()).allMatch(CandleRow::empty);
assertThat(presenter.overview(rows).mostActive().marketId()).isEqualTo("diamond-usd");
```

- [x] **Step 2: Run the presenter test to verify it fails**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MarketDashboardPresenterTest test`

Expected: compilation failure because dashboard presenter types do not exist.

- [x] **Step 3: Implement immutable records and deterministic presentation**

```java
long maximum = visible.stream().mapToLong(DepthLevel::quantity).max().orElse(1L);
int strength = (int) Math.ceil(level.quantity() * 8.0D / maximum);
return new DepthRow(level.price(), level.quantity(), cumulative, level.executable(), strength, false);
```

- [x] **Step 4: Run the presenter test to verify it passes**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MarketDashboardPresenterTest test`

Expected: PASS.

### Task 3: View Facade And Inventory Pages

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeViewService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketListPage.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDetailPage.java`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDashboardPresenterTest.java`

**Interfaces:**
- Consumes `ExchangeViewService.marketDashboard(String): CompletableFuture<MarketDashboardSnapshot>`.
- Consumes `ExchangeViewService.marketOverview(): CompletableFuture<MarketOverviewSnapshot>`.

- [x] **Step 1: Add a failing integration-shaped view test**

```java
assertThat(views.marketDashboard("diamond-usd").join().bids()).hasSizeLessThanOrEqualTo(5);
```

- [x] **Step 2: Run it to verify it fails**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=ExchangeViewServiceTest test`

Expected: compilation failure because `marketDashboard` does not exist.

- [x] **Step 3: Implement view composition and render fixed-slot icons**

```java
views.marketDashboard(request.marketId()).whenComplete((dashboard, failure) ->
    QuickShop.folia().getScheduler().runAtEntityLater(player,
        () -> render(page, player, dashboard, failure), 1L));
```

- [x] **Step 4: Run focused UI/data tests**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MarketDashboardPresenterTest,ExchangeViewServiceTest test`

Expected: PASS.

### Task 4: Full Verification And Package

**Files:**
- Modify: implementation and tests from Tasks 1–3 only.

- [x] **Step 1: Run the full Exchange test suite**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 test`

Expected: all Exchange and required reactor tests pass.

- [x] **Step 2: Build the addon JAR**

Run: `mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -DskipTests package`

Expected: `addon/exchange/target/Addon-Exchange-6.3.0.0-SNAPSHOT-11.jar` is generated.

- [x] **Step 3: Review the final diff**

Run: `git diff --check && git diff -- addon/exchange docs/superpowers`

Expected: no whitespace errors and only the planned Exchange GUI, data, test, and documentation changes.
