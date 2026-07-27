# Exchange Exact Market Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore order priority, sequence counters, reference-price history, and circuit-breaker state exactly after restart or runtime version mismatch.

**Architecture:** Add two versioned risk fields to market state, read one locked market snapshot, and rebuild immutable runtime state through a focused recovery service. Normal recovery reads only open orders and the five-minute trade window; V1 rows perform one full replay and persist the reconstructed metadata before publication.

**Tech Stack:** Java 21, JDBC, SQLite, MySQL 8.4, JUnit 5, AssertJ, Testcontainers, Maven

---

### Task 1: Persist Exact Risk Metadata In Schema V2

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV2.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MySqlMigrationIT.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`

- [ ] **Step 1: Write migration upgrade tests**

Add SQLite and MySQL assertions that two schema-version rows exist after repeated migration and
that `discovery_quantity` and `circuit_breaker_level` exist. Build a V1 SQLite database manually,
insert one market state, run `MigrationRunner.migrate()`, and assert both new values are SQL null.

```java
assertThat(rowCount(connection, names.schemaVersion())).isEqualTo(2);
assertThat(columnExists(connection, names.marketState(), "discovery_quantity")).isTrue();
assertThat(columnExists(connection, names.marketState(), "circuit_breaker_level")).isTrue();
assertThat(nullableLong(connection, names.marketState(), "discovery_quantity")).isNull();
```

- [ ] **Step 2: Run migration tests and verify red**

Run:

```powershell
mvn -pl addon/exchange -Dtest=MigrationRunnerTest,MySqlMigrationIT test
```

Expected: FAIL because schema version 2 and both columns do not exist.

- [ ] **Step 3: Implement idempotent V2 column migration**

Define two nullable columns so pre-V2 rows are distinguishable from fresh exact rows:

```java
public final class SchemaV2 {
  public static List<ColumnDefinition> columns(TableNames tables, SqlDialect dialect) {
    return List.of(
        new ColumnDefinition(tables.marketState(), "discovery_quantity", dialect.longType()),
        new ColumnDefinition(tables.marketState(), "circuit_breaker_level", "INTEGER"));
  }

  public record ColumnDefinition(String table, String name, String type) {}
}
```

`MigrationRunner` must use JDBC metadata to add each missing column, then insert schema version 2.
This preserves retry safety for MySQL's implicitly committed DDL.

```java
if (!columnExists(connection, column.table(), column.name())) {
  statement.execute("ALTER TABLE " + column.table() + " ADD COLUMN "
      + column.name() + " " + column.type());
}
recordVersion(connection, 2);
```

Extend `MarketState` with nullable `Long discoveryQuantity` and
`Integer circuitBreakerLevel`. Select and update both columns in `JdbcExchangeRepository`; bind
SQL null only for an unmigrated V1 row. Update every fixture market insert to write `0,0`.

- [ ] **Step 4: Run migration and existing persistence tests**

Run:

```powershell
mvn -pl addon/exchange -Dtest=MigrationRunnerTest,MySqlMigrationIT,JdbcBalanceRepositoryTest,JdbcLedgerTest test
```

Expected: PASS on SQLite and MySQL when Docker is available.

- [ ] **Step 5: Commit the migration**

```powershell
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange
git commit -m "feat(exchange): persist exact market risk state"
```

### Task 2: Make Risk Objects Exactly Restorable

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/ReferencePriceTracker.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/CircuitBreaker.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk/MarketRiskTest.java`

- [ ] **Step 1: Write exact state round-trip tests**

```java
@Test
void restoresWindowSamplesAndSaturatedDiscoveryQuantity() {
  PriceSample sample = new PriceSample(new BigDecimal("105.00"), 50, Instant.EPOCH);
  ReferencePriceTracker restored = ReferencePriceTracker.restored(
      new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2, 50, List.of(sample));
  restored.record(new BigDecimal("105.00"), 100, Instant.EPOCH.plusSeconds(1));
  assertThat(restored.discoveryQuantity()).isEqualTo(100);
  assertThat(restored.referenceAt(Instant.EPOCH.plusSeconds(1)))
      .isEqualByComparingTo("105.00");
}

@Test
void restoresExactBreakerLevelAfterResume() {
  CircuitBreaker restored = CircuitBreaker.restored(RiskLimits.defaults(), 1, null);
  assertThat(restored.level()).isEqualTo(1);
  assertThat(restored.onPrice(new BigDecimal("120.00"), new BigDecimal("100.00"), Instant.EPOCH)
      .level()).isEqualTo(2);
}
```

- [ ] **Step 2: Run the focused risk test and verify red**

Run: `mvn -pl addon/exchange -Dtest=MarketRiskTest test`

Expected: FAIL because exact restore overloads and state accessors do not exist.

- [ ] **Step 3: Implement bounded, validated restoration**

`ReferencePriceTracker.record` must saturate at `discoveryQuantity`; exact restoration validates
`0 <= cumulative <= target`, validates sample chronology and positive values, then copies samples.
Expose `discoveryQuantity()` and `samples()` as immutable snapshots. Add
`CircuitBreaker.restored(RiskLimits, int, Instant)` plus `level()` and `haltedUntil()`; reject
levels outside 0..2.

```java
long remainingDiscovery = discoveryQuantity - cumulativeDiscoveryQuantity;
cumulativeDiscoveryQuantity = Math.addExact(cumulativeDiscoveryQuantity,
    Math.min(quantity, remainingDiscovery));
```

- [ ] **Step 4: Run all core tests**

Run:

```powershell
mvn -pl addon/exchange -Dtest=MarketRiskTest,OrderBookTest,LimitMatchingTest,FeesMarketAndSelfTradeTest,MatchingConservationTest,DomainValidationTest,MarketDispatcherTest test
```

Expected: PASS.

- [ ] **Step 5: Commit restorable risk state**

```powershell
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk
git commit -m "feat(exchange): restore exact risk history"
```

### Task 3: Load And Assemble A Locked Market Snapshot

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/MarketTradeSample.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/MarketSnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/RecoveredMarket.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Create: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryServiceTest.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`

- [ ] **Step 1: Write recovery behavior and corruption tests**

Cover two same-price sells, a partially filled maker, exact persisted counters, the `50 @ 105`
restart case, expired samples with retained discovery, resumed level-one escalation, and rejection
of a priority sequence above the market counter.

```java
RecoveredMarket recovered = fixture.recovery().recover("diamond-usd", fixture.now());
assertThat(recovered.book().orders(OrderSide.SELL))
    .extracting(Order::prioritySequence).containsExactly(1L, 2L);
assertThat(recovered.prioritySequence()).isEqualTo(fixture.marketPrioritySequence());
assertThat(recovered.referencePrices().discoveryQuantity()).isEqualTo(50);
```

- [ ] **Step 2: Run recovery tests and verify red**

Run: `mvn -pl addon/exchange -Dtest=OrderBookRecoveryServiceTest test`

Expected: FAIL because snapshot and recovery types do not exist.

- [ ] **Step 3: Add snapshot value types and transaction port**

```java
public record MarketTradeSample(BigDecimal price, long quantity,
                                long matchSequence, Instant executedAt) {}

public record MarketSnapshot(MarketState state, List<PersistedOrder> openOrders,
                             List<MarketTradeSample> recentTrades,
                             List<MarketTradeSample> fullHistory,
                             long maximumPrioritySequence, long maximumMatchSequence) {}
```

Add `ExchangeTransaction.marketSnapshot(MarketState state, Instant cutoff)`. JDBC queries must
order trades by `match_sequence` and append `FOR UPDATE` on MySQL. Query full history only when
either V2 field is null. Query maxima across all orders/trades and preserve exact decimals and
timestamps.

- [ ] **Step 4: Implement pure validation and reconstruction**

`OrderBookRecoveryService.recover(marketId, recoveredAt)` opens the transaction, locks market
state first, reads the snapshot, validates all IDs/statuses/sequences/ranges, and assembles
`RecoveredMarket`. For V1, replay full history, write derived risk fields through
`updateMarketState`, and return the incremented committed version. For V2, restore from recent
samples and persisted metadata.

```java
snapshot.openOrders().stream()
    .map(PersistedOrder::order)
    .sorted(Comparator.comparingLong(Order::prioritySequence))
    .forEach(book::add);
```

Expose a transaction-bound overload so order settlement can rebuild under its existing market
row lock without nesting a repository transaction.

- [ ] **Step 5: Run recovery, service, and persistence tests**

Run:

```powershell
mvn -pl addon/exchange -Dtest=OrderBookRecoveryServiceTest,PersistentOrderServiceTest,Jdbc*Test test
```

Expected: PASS.

- [ ] **Step 6: Commit snapshot recovery**

```powershell
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service
git commit -m "feat(exchange): rebuild exact market snapshots"
```

### Task 4: Integrate Recovery With Persistent Settlement

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderServiceTest.java`

- [ ] **Step 1: Strengthen restart and version-mismatch tests**

Change the existing restart test to trade `50 @ 105`, instantiate a service with an independent
runtime coordination key, trade `1 @ 105`, and require `102.55`. Add a breaker test that performs
level one, resumes, records a benign trade, resets runtime, then requires a later 20% breach to be
level two with one HIGH alert. Add corrupt-snapshot coverage requiring `RECOVERING` and no new
order/trade.

- [ ] **Step 2: Run service tests and verify the exact restart case is red**

Run: `mvn -pl addon/exchange -Dtest=PersistentOrderServiceTest test`

Expected: FAIL with the old approximate restart reference or breaker level.

- [ ] **Step 3: Recover inside the locked settlement transaction**

Replace `runtimeRisk(MarketState)` approximate fallback. If the committed runtime version differs
from the locked database version, call the transaction-bound recovery overload and use its exact
tracker, breaker, book, and possibly upgraded V1 market state. Continue settlement with that
state's version. Persist `prices.discoveryQuantity()` and `breaker.level()` in every
`updateRiskState` result.

Add `recoverFromDatabase()` for startup. Hold the shared runtime monitor across standalone
recovery and publication, and publish all four runtime components together only after repository
commit succeeds.

- [ ] **Step 4: Run complete SQLite tests**

Run: `mvn -pl addon/exchange -Dtest='*Test' test`

Expected: PASS with no Docker requirement.

- [ ] **Step 5: Commit service integration**

```powershell
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service
git commit -m "feat(exchange): recover runtime before settlement"
```

### Task 5: Prove MySQL Snapshot Currency And Finish Task 6

**Files:**
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MySqlMigrationIT.java`
- Modify: `.superpowers/sdd/progress.md`
- Create: `.superpowers/sdd/phase2/task-6-report.md`

- [ ] **Step 1: Extend the real MySQL repeatable-read test**

In the existing two-transaction test, commit an order plus trade and V2 market state while the
reader already owns an old consistent-read snapshot. After the reader acquires the market lock,
call `marketSnapshot` and assert it sees the committed order, recent trade, maxima, discovery
quantity, breaker level, and market version.

- [ ] **Step 2: Run focused MySQL and recovery verification**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
$env:TESTCONTAINERS_HOST_OVERRIDE='127.0.0.1'
& 'C:\Users\ztrnb\AppData\Local\Temp\codex-maven-3.9.11-run2\apache-maven-3.9.11\bin\mvn.cmd' -pl addon/exchange '-Dtest=OrderBookRecoveryServiceTest,PersistentOrderServiceTest,MySqlMigrationIT' '-Dapi.version=1.44' verify
```

Expected: PASS, including the current-read concurrency test.

- [ ] **Step 3: Run the full module verification**

Run the same Maven executable with:

```powershell
-pl addon/exchange '-Dtest=*Test,*IT' '-Dapi.version=1.44' verify
```

Expected: BUILD SUCCESS with all SQLite tests and all available MySQL 8.4 tests passing.

- [ ] **Step 4: Review, document, commit, and push**

Review the Task 6 diff against the approved specification, record exact test counts and any
remaining minor follow-ups in `.superpowers/sdd/phase2/task-6-report.md`, update progress, then:

```powershell
git add .superpowers/sdd addon/exchange docs/superpowers/plans/2026-07-27-exchange-exact-market-recovery.md
git commit -m "test(exchange): verify exact market recovery"
git push origin codex/exchange-order-book
```

Expected: local and remote `codex/exchange-order-book` point to the same Task 6 head. Continue to
Phase 2 Task 7; do not mark the complete-plugin goal finished.
