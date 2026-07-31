# Exchange Trusted Market Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace volume-only reference pricing with a durable, participant-aware trusted price that two accounts cannot control through repeated trades.

**Architecture:** Add a pure `core.trust` domain that calculates liquidity tiers, rolling absolute-move budgets, anchor limits, and maintenance reversion without Bukkit or JDBC dependencies. Persist the trusted state and immutable influence/adjustment events in the same settlement transaction, restore them before opening the market, then make quote, cage, slippage, breaker, and chart readers consume the trusted price while preserving raw trades.

**Tech Stack:** Java 21, BigDecimal, Maven, JUnit 5, AssertJ, JDBC, SQLite/MySQL, existing Exchange single-writer runtime

---

## File map

- `core/trust/TrustedPricePolicy.java`: validated policy and three tier defaults.
- `core/trust/LiquidityTier.java`: `LOW`, `GROWING`, `STABLE` enum.
- `core/trust/TrustedPriceState.java`: immutable trusted/guidance state.
- `core/trust/TradeInfluence.java`: immutable participant-aware influence event.
- `core/trust/TrustedPriceAdjustment.java`: maintenance/admin non-trade change event.
- `core/trust/LiquidityClassifier.java`: deterministic 24-hour participant classification.
- `core/trust/TrustedPriceEngine.java`: hard movement-budget calculation.
- `core/trust/TrustedPriceMaintenance.java`: no-trade anchor reversion.
- `repository/TrustedMarketSnapshot.java`: state plus bounded events for recovery.
- `persistence/SchemaV5.java`: trusted state, influence, adjustment tables and indexes.
- `repository/ExchangeTransaction.java`: transactional trusted-state contract.
- `persistence/JdbcExchangeRepository.java`: SQLite/MySQL storage implementation.
- `service/OrderBookRecoveryService.java`: restore trusted state instead of VWAP samples.
- `service/PersistentOrderService.java`: transactionally calculate and store trusted influence.
- `marketdata/MarketQuote.java`: expose liquidity tier and trusted reference.

### Task 1: Trusted policy and state values

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/LiquidityTier.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPricePolicy.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceState.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPricePolicyTest.java`

- [ ] **Step 1: Write failing policy validation and default tests**

```java
@Test
void defaultsMatchApprovedLowLiquidityLimits() {
  TrustedPricePolicy policy = TrustedPricePolicy.defaults();
  TrustedPricePolicy.Tier low = policy.tier(LiquidityTier.LOW);
  assertThat(policy.budgetWindow()).isEqualTo(Duration.ofHours(6));
  assertThat(policy.confidenceWindow()).isEqualTo(Duration.ofHours(24));
  assertThat(low.perTradeCap()).isEqualByComparingTo("0.005");
  assertThat(low.pairBudget()).isEqualByComparingTo("0.0075");
  assertThat(low.anchorBand()).isEqualByComparingTo("0.10");
}

@Test
void rejectsBudgetOrderThatWouldBypassPairProtection() {
  assertThatThrownBy(() -> new TrustedPricePolicy.Tier(
      bd("0.01"), bd("0.02"), bd("0.01"), bd("0.011"), bd("0.10"), bd("0")))
      .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the policy test and verify RED**

Run:

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=TrustedPricePolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because `core.trust` types do not exist.

- [ ] **Step 3: Implement immutable validated values**

```java
public enum LiquidityTier { LOW, GROWING, STABLE }

public record TrustedPricePolicy(
    Duration budgetWindow, Duration confidenceWindow, Map<LiquidityTier, Tier> tiers) {
  public record Tier(BigDecimal perTradeCap, BigDecimal marketBudget,
                     BigDecimal accountBudget, BigDecimal pairBudget,
                     BigDecimal anchorBand, BigDecimal reversionPerHour) {
    public Tier {
      requireRatio(perTradeCap); requireRatio(marketBudget); requireRatio(accountBudget);
      requireRatio(pairBudget); requireRatio(anchorBand); requireRatio(reversionPerHour);
      if (pairBudget.compareTo(accountBudget) > 0
          || accountBudget.compareTo(marketBudget) > 0
          || perTradeCap.compareTo(marketBudget) > 0) {
        throw new IllegalArgumentException("trusted price budgets are inconsistent");
      }
    }
  }

  public Tier tier(LiquidityTier tier) { return tiers.get(Objects.requireNonNull(tier)); }
}

public record TrustedPriceState(
    String marketId, BigDecimal trustedPrice, BigDecimal guidancePrice,
    Instant lastEvaluatedAt, LiquidityTier liquidityTier,
    long policyVersion, long lastMatchSequence, long stateVersion) {
  public TrustedPriceState withLiquidityTier(LiquidityTier tier) {
    return new TrustedPriceState(marketId, trustedPrice, guidancePrice, lastEvaluatedAt,
        tier, policyVersion, lastMatchSequence, stateVersion);
  }
}
```

Implement `defaults()` with every value from the approved spec, copy the tier map, require positive windows/prices/versions, and retain `priceScale + 8` precision in callers rather than rounding in this record.

- [ ] **Step 4: Run test and verify GREEN**

Expected: `TrustedPricePolicyTest` passes.

- [ ] **Step 5: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPricePolicyTest.java
git commit -m "feat(exchange): define trusted price policy"
```

### Task 2: Participant-aware liquidity classification

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TradeInfluence.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/LiquiditySnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/LiquidityClassifier.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/LiquidityClassifierTest.java`

- [ ] **Step 1: Write failing two-player and diverse-market tests**

```java
@Test
void twoAccountsRemainLowRegardlessOfTradeCount() {
  UUID a = id(1); UUID b = id(2);
  List<TradeInfluence> events = IntStream.range(0, 100)
      .mapToObj(i -> influence(a, b, NOW.minusSeconds(i * 60L), 64))
      .toList();
  assertThat(classifier.classify(events, NOW, 5).tier()).isEqualTo(LiquidityTier.LOW);
}

@Test
void diverseTimeDistributedMarketBecomesStable() {
  List<TradeInfluence> events = stableFixtureWithEightAccountsSixPairsTwentyTrades();
  LiquiditySnapshot result = classifier.classify(events, NOW, 5);
  assertThat(result.tier()).isEqualTo(LiquidityTier.STABLE);
  assertThat(result.participants()).isGreaterThanOrEqualTo(8);
  assertThat(result.activeBuckets()).isGreaterThanOrEqualTo(4);
}
```

- [ ] **Step 2: Run test and verify RED**

Expected: missing `LiquidityClassifier` and influence event types.

- [ ] **Step 3: Implement canonical pair keys, capped counts, and concentration**

```java
public record TradeInfluence(
    UUID tradeId, String marketId, long matchSequence,
    UUID buyerAccountId, UUID sellerAccountId, String pairKey,
    BigDecimal tradePrice, long quantity, BigDecimal referenceBefore,
    BigDecimal referenceAfter, BigDecimal requestedMove, BigDecimal acceptedMove,
    BigDecimal quantityFactor, LiquidityTier tier, long policyVersion,
    Set<LimitReason> reasons, Instant executedAt) {
  public static String pairKey(UUID left, UUID right) {
    String a = left.toString(); String b = right.toString();
    return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
  }
}

public LiquiditySnapshot classify(List<TradeInfluence> source, Instant now, long lot) {
  List<TradeInfluence> events = source.stream()
      .filter(event -> !event.executedAt().isBefore(now.minus(policy.confidenceWindow())))
      .sorted(comparing(TradeInfluence::executedAt).thenComparingLong(TradeInfluence::matchSequence))
      .toList();
  // Count distinct accounts/pairs, cap a pair at five effective trades and an account at ten,
  // group UTC epoch seconds into four-hour buckets, and use min(quantity, 5 * lot) weights.
  Metrics value = metrics(events, lot);
  LiquidityTier tier = value.participants() >= 8 && value.pairs() >= 6
      && value.effectiveTrades() >= 20 && value.activeBuckets() >= 4
      && value.accountConcentration().compareTo(new BigDecimal("0.35")) <= 0
      && value.pairConcentration().compareTo(new BigDecimal("0.25")) <= 0
          ? LiquidityTier.STABLE
          : value.participants() >= 4 && value.pairs() >= 3
              && value.effectiveTrades() >= 6 && value.activeBuckets() >= 2
              && value.accountConcentration().compareTo(new BigDecimal("0.60")) <= 0
              && value.pairConcentration().compareTo(new BigDecimal("0.50")) <= 0
                  ? LiquidityTier.GROWING : LiquidityTier.LOW;
  return value.snapshot(tier);
}
```

Implement stable thresholds first, then growing thresholds, exactly as section 5 of the spec. Define zero-event concentration as zero.

- [ ] **Step 4: Add boundary tests and run GREEN**

Test one missing participant, pair, bucket, count, account concentration, and pair concentration for each tier boundary. Expected: all `LiquidityClassifierTest` tests pass.

- [ ] **Step 5: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/LiquidityClassifierTest.java
git commit -m "feat(exchange): classify trusted market liquidity"
```

### Task 3: Hard influence budgets and anchor limits

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceEngine.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceEngineTest.java`

- [ ] **Step 1: Write the approved two-player manipulation test**

```java
@Test
void pairBudgetStopsAlternatingTwoPlayerManipulation() {
  TrustedPriceState start = state("100.0000000000", "100.00", LiquidityTier.LOW);
  Trade first = trade(A, B, "115.00", 5, 1, NOW);
  TrustedPriceEngine.Result one = engine.evaluate(start, policy, first, List.of(), 100, 2);
  assertThat(one.state().trustedPrice()).isEqualByComparingTo("100.5000000000");

  Trade second = trade(B, A, "85.00", 5, 2, NOW.plusSeconds(1));
  TrustedPriceEngine.Result two = engine.evaluate(
      one.state(), policy, second, List.of(one.influence()), 100, 2);
  assertThat(two.state().trustedPrice()).isEqualByComparingTo("100.2487500000");

  Trade third = trade(A, B, "115.00", 5, 3, NOW.plusSeconds(2));
  TrustedPriceEngine.Result three = engine.evaluate(
      two.state(), policy, third, List.of(one.influence(), two.influence()), 100, 2);
  assertThat(three.influence().acceptedMove()).isZero();
  assertThat(three.influence().reasons()).contains(LimitReason.LIMITED_BY_PAIR);
}
```

- [ ] **Step 2: Run test and verify RED**

Expected: `TrustedPriceEngine` is missing.

- [ ] **Step 3: Implement deterministic budget evaluation**

```java
public Result evaluate(TrustedPriceState state, TrustedPricePolicy policy, Trade trade,
                       List<TradeInfluence> history, long discoveryQuantity, int priceScale) {
  TrustedPricePolicy.Tier limits = policy.tier(state.liquidityTier());
  long lot = Math.max(1L, Math.ceilDiv(discoveryQuantity, 20L));
  BigDecimal factor = BigDecimal.valueOf(trade.quantity())
      .divide(BigDecimal.valueOf(lot), priceScale + 12, RoundingMode.DOWN)
      .min(BigDecimal.ONE);
  BigDecimal distance = trade.price().subtract(state.trustedPrice()).abs()
      .divide(state.trustedPrice(), priceScale + 12, RoundingMode.DOWN);
  BigDecimal requested = distance.min(limits.perTradeCap()).multiply(factor);
  Remaining remaining = remaining(history, trade, trade.executedAt(), policy, limits);
  BigDecimal accepted = requested.min(remaining.market()).min(remaining.buyer())
      .min(remaining.seller()).min(remaining.pair()).min(anchorAllowance(state, trade, limits));
  BigDecimal signed = trade.price().compareTo(state.trustedPrice()) < 0
      ? accepted.negate() : accepted;
  BigDecimal next = state.trustedPrice().multiply(BigDecimal.ONE.add(signed))
      .setScale(priceScale + 8, RoundingMode.HALF_UP);
  Set<LimitReason> reasons = limitReasons(requested, accepted, remaining,
      anchorAllowance(state, trade, limits), limits.perTradeCap(), distance, factor);
  TrustedPriceState nextState = new TrustedPriceState(state.marketId(), next,
      state.guidancePrice(), trade.executedAt(), state.liquidityTier(), state.policyVersion(),
      trade.matchSequence(), Math.addExact(state.stateVersion(), 1));
  TradeInfluence influence = new TradeInfluence(trade.tradeId(), trade.marketId(),
      trade.matchSequence(), trade.buyerAccountId(), trade.sellerAccountId(),
      TradeInfluence.pairKey(trade.buyerAccountId(), trade.sellerAccountId()),
      trade.price(), trade.quantity(), state.trustedPrice(), next, requested, accepted,
      factor, state.liquidityTier(), state.policyVersion(), reasons, trade.executedAt());
  return new Result(nextState, influence);
}

public record Result(TrustedPriceState state, TradeInfluence influence) { }
```

`remaining(...)` sums `acceptedMove.abs()` for the market, each account, and canonical pair after excluding `executedAt < trade.executedAt - budgetWindow`, then returns `max(0, configuredLimit - consumed)` for every dimension. `anchorAllowance(...)` computes the approved guidance band, returns zero for outward motion when already outside, and otherwise returns the relative move to the nearest boundary. `limitReasons(...)` adds every cap equal to the accepted minimum, including `LIMITED_BY_TRADE` when distance times quantity factor exceeded the per-trade request. Count an event against both accounts regardless of side and validate chronological match sequences.

- [ ] **Step 4: Add all hard-limit tests and run GREEN**

Cover quantity factor, market budget, buyer budget, seller budget, pair key symmetry, reverse trades, event expiry, anchor upper/lower bounds, already-outside inward-only motion, tick display precision, and zero-distance trades.

- [ ] **Step 5: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceEngine.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceEngineTest.java
git commit -m "feat(exchange): bound trusted trade influence"
```

### Task 4: Durable no-trade reversion

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceAdjustment.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceMaintenance.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceMaintenanceTest.java`

- [ ] **Step 1: Write failing low/growing/stable reversion tests**

```java
@Test
void lowLiquidityReturnsAtHalfPercentPerHourWithoutCreatingTrade() {
  Result result = maintenance.evaluate(state("110.00", "100.00", LOW), policy,
      NOW.plus(Duration.ofHours(2)), 2);
  assertThat(result.state().trustedPrice()).isEqualByComparingTo("108.9000000000");
  assertThat(result.adjustment().type()).isEqualTo(AdjustmentType.ANCHOR_REVERSION);
}

@Test
void stableMarketDoesNotGenerateZeroAdjustment() {
  assertThat(maintenance.evaluate(state("110", "100", STABLE), policy,
      NOW.plus(Duration.ofHours(2)), 2).adjustment()).isNull();
}
```

- [ ] **Step 2: Verify RED, implement, and verify GREEN**

```java
BigDecimal hours = BigDecimal.valueOf(Duration.between(
    state.lastEvaluatedAt(), now).toMillis())
    .divide(BigDecimal.valueOf(Duration.ofHours(1).toMillis()), scale + 12, DOWN);
BigDecimal move = state.guidancePrice().subtract(state.trustedPrice()).abs()
    .divide(state.trustedPrice(), scale + 12, DOWN)
    .min(policy.tier(state.liquidityTier()).reversionPerHour().multiply(hours));
```

Return no adjustment for non-positive elapsed time, zero rate, or equal price. Otherwise produce one adjustment and increment `stateVersion`; do not write Trade, volume, or Candle.

- [ ] **Step 3: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/trust addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/trust/TrustedPriceMaintenanceTest.java
git commit -m "feat(exchange): add trusted price anchor reversion"
```

### Task 5: Schema V5 and repository contracts

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV5.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/TrustedMarketSnapshot.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/TableNames.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java`

- [ ] **Step 1: Add failing schema assertions**

Assert migration version 5 exists; all three tables exist; influence `trade_id` is unique; and market/time, buyer/time, seller/time, pair/time indexes exist under a non-empty prefix.

- [ ] **Step 2: Verify RED and define transactional contract**

```java
TrustedMarketSnapshot trustedMarketSnapshot(
    String marketId, Instant budgetCutoff, Instant confidenceCutoff) throws SQLException;
void insertTradeInfluence(TradeInfluence influence) throws SQLException;
void insertTrustedAdjustment(TrustedPriceAdjustment adjustment) throws SQLException;
void updateTrustedPriceState(TrustedPriceState state, long expectedVersion) throws SQLException;
void upsertCandle(Candle candle) throws SQLException;
```

`TrustedMarketSnapshot` contains one `TrustedPriceState`, bounded influence events, and bounded adjustments. Add table-name methods `trustedMarketState()`, `trustedMarketInfluence()`, and `trustedMarketAdjustment()`.

- [ ] **Step 3: Implement idempotent V5 DDL**

Create exact tables `trusted_market_state`, `trusted_market_influence`, and `trusted_market_adjustment` (with the configured Exchange prefix), using state columns from section 8.1, influence columns from 8.2, and adjustment columns from 8.3 of the spec. Use dialect decimal/long types, foreign keys to market/trade where existing schema conventions permit, `CHECK` constraints for positive prices and non-negative ratios, and `MigrationRunner.ensureIndex` for every required index. Record version 5 only after tables and indexes exist.

- [ ] **Step 4: Run migration tests GREEN for SQLite and optional MySQL IT**

Run:

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest=MigrationRunnerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: SQLite tests pass; MySQL tests remain conditional on Docker.

- [ ] **Step 5: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java
git commit -m "feat(exchange): add trusted market schema"
```

### Task 6: JDBC trusted state persistence

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcTrustedMarketTest.java`

- [ ] **Step 1: Write failing round-trip, optimistic-lock, and rollback tests**

```java
@Test
void stateInfluenceAndAdjustmentRoundTripInSequence() throws Exception {
  repository.inTransaction(tx -> {
    tx.insertTradeInfluence(influence);
    tx.insertTrustedAdjustment(adjustment);
    tx.updateTrustedPriceState(nextState, current.stateVersion());
    return null;
  });
  TrustedMarketSnapshot restored = repository.inTransaction(tx ->
      tx.trustedMarketSnapshot(MARKET, NOW.minus(6, HOURS), NOW.minus(24, HOURS)));
  assertThat(restored.state()).isEqualTo(nextState);
  assertThat(restored.influences()).containsExactly(influence);
  assertThat(restored.adjustments()).containsExactly(adjustment);
}
```

Also force an exception after inserts and prove no rows/state changes remain; update with wrong expected version must affect zero rows and throw.

- [ ] **Step 2: Verify RED and implement prepared-statement mappings**

Use canonical decimal strings for ratios/prices, epoch milliseconds for instants, enum names for tier/type/reasons, ordered reads by `executed_at, match_sequence`, and the existing transaction connection. Insert state rows during market initialization with `guidance = existing reference = base price`, policy version 1, match sequence 0, and state version 0.

- [ ] **Step 3: Run JDBC test GREEN**

Expected: round trip is value-equal, duplicate trade influence fails, wrong version fails, and rollback leaves no partial state.

- [ ] **Step 4: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcTrustedMarketTest.java
git commit -m "feat(exchange): persist trusted market state"
```

### Task 7: Recovery and old-database migration

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/MarketTradeSample.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/RecoveredMarket.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/OrderBookRecoveryService.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/TrustedMarketRecoveryTest.java`

- [ ] **Step 1: Write failing restart and legacy migration tests**

Restart after exhausting a pair budget, then prove the same pair receives zero influence until expiry. Create a V4 database with trades containing buyer/seller IDs but no trusted tables, migrate, and prove guidance/trusted price initialize from persisted reference and influences replay in match order.

- [ ] **Step 2: Extend participant-aware samples**

```java
public record MarketTradeSample(
    UUID tradeId, UUID buyerAccountId, UUID sellerAccountId,
    BigDecimal price, long quantity, long matchSequence, Instant executedAt) { }
```

Update recent/full-history SQL to select `trade_id,buyer_account_id,seller_account_id`. Do not synthesize anonymous parties.

- [ ] **Step 3: Replace recovered VWAP tracker with trusted state**

```java
public record RecoveredMarket(
    OrderBook book, TrustedPriceState trustedPriceState,
    List<TradeInfluence> recentInfluences,
    CircuitBreaker circuitBreaker, MarketState state) { }
```

If state/events validate, restore them. For a V4 database, seed guidance/trusted from `market_state.reference_price`, replay complete participant-aware trade history using the default policy, insert events/state transactionally, and mark migration complete. Any sequence, duplicate, or state mismatch enters `RECOVERING`/`PAUSED` and preserves data.

- [ ] **Step 4: Run recovery tests GREEN and existing recovery suite**

Expected: old FIFO/order recovery still passes, pair budget survives restart, and old history is replayed only once.

- [ ] **Step 5: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service
git commit -m "feat(exchange): recover trusted market influence"
```

### Task 8: Settlement, risk, quote, and breaker integration

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketQuote.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/CircuitBreaker.java`
- Delete after callers migrate: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/ReferencePriceTracker.java`
- Delete after callers migrate: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/PriceSample.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/TrustedSettlementIntegrationTest.java`

- [ ] **Step 1: Write failing full settlement assertions**

Place real orders from two accounts at 115 and 85. Assert both trades settle and raw Candle/last price reflect them, while quote reference follows `100.50` then `100.24875`; a third trade settles with zero influence. Force influence persistence failure and assert trade, balances, ledger, Candle, and state all roll back.

- [ ] **Step 2: Integrate trusted calculation in the settlement transaction**

After creating each Trade but before committing:

```java
LiquiditySnapshot liquidity = classifier.classify(recentInfluences, trade.executedAt(), lot);
TrustedPriceState classified = trustedState.withLiquidityTier(liquidity.tier());
TrustedPriceEngine.Result trusted = trustedPriceEngine.evaluate(
    classified, trustedPolicy, trade, recentInfluences, discoveryQuantity, rules.priceScale());
tx.insertTrade(trade);
tx.insertTradeInfluence(trusted.influence());
tx.updateTrustedPriceState(trusted.state(), classified.stateVersion());
tx.upsertCandle(rawCandleFor(trade));
```

Return the committed Trade, Candle delta, influence, and trusted state from `repository.inTransaction`; only then call `marketData.acceptCommitted(...)` and replace the in-memory trusted state. Use trusted price for cage/slippage/breaker inputs. Preserve `MarketState.lastPrice` as raw last trade; keep its legacy `referencePrice` mirrored to trusted display precision during compatibility migration until all readers move.

Add `long discoveryQuantity` to `PersistentOrderService` construction and update `ExchangeRuntimeFactory` plus `ExchangeServiceFixture` in this task. The runtime value comes from `MarketDefinition.structural().discoveryQuantity()`; tests use `100L`. This keeps Task 8 compiling before the YAML scheduling work in Task 9.

- [ ] **Step 3: Extend quote without hiding raw price**

```java
public record MarketQuote(
    String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
    LiquidityTier liquidityTier, BigDecimal bestBid, BigDecimal bestAsk,
    BigDecimal change24h, long volume24h, BigDecimal notional24h,
    MarketStatus status, Instant asOf) { }
```

`lastPrice` remains raw, `referencePrice` is trusted, and circuit-breaker movement is calculated from accepted trusted changes. Audit zero-weight/extreme raw trades without halting solely because they were deweighted.

- [ ] **Step 4: Run integration and existing risk tests GREEN**

Expected: settlement invariants pass; existing cage/slippage tests use trusted price; no test redefines raw last price as trusted.

- [ ] **Step 5: Remove old tracker only after search has no production callers**

Run:

```text
rg -n "ReferencePriceTracker|PriceSample" addon/exchange/src/main/java
```

Expected before deletion: only files being deleted; after deletion: no matches.

- [ ] **Step 6: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/TrustedSettlementIntegrationTest.java
git commit -m "feat(exchange): use trusted price for market risk"
```

### Task 9: Configuration, maintenance scheduling, and verification

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketDefinition.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistry.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify: `addon/exchange/src/main/resources/markets.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/TrustedPriceMaintenanceIntegrationTest.java`

- [ ] **Step 1: Write failing config and durable-maintenance tests**

Load defaults and assert all policy values. Advance a mutable clock two hours, run maintenance, restart runtime, and assert the reversion adjustment and price are restored without any added Candle volume.

- [ ] **Step 2: Parse policy and schedule minute maintenance**

Add `trusted-market` YAML keys mirroring the spec. Parse into `TrustedPricePolicy`, version it through market risk configuration, and schedule maintenance through the existing Exchange writer plus Folia-safe scheduler. Persist adjustment/state first; publish runtime state after commit. Do not run one task per market if a single bounded sweep can handle all markets.

- [ ] **Step 3: Run targeted tests**

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest='TrustedPrice*Test,LiquidityClassifierTest,TrustedMarketRecoveryTest,TrustedSettlementIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all trusted-market tests pass.

- [ ] **Step 4: Run Exchange reactor verification**

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 verify
```

Expected: Reactor success and all Exchange tests pass.

- [ ] **Step 5: Verify source compatibility boundary**

Build or compile the addon against the local QSH 6.2.0.11 worktree/JAR, then inspect linkage for direct `customName`, `setLockEmptySlots`, or new-only TNML calls. Expected: compatibility wrappers remain the only version bridge and the addon loads on a 6.2.0.11 test server.

- [ ] **Step 6: Commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime addon/exchange/src/main/resources addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/TrustedPriceMaintenanceIntegrationTest.java
git commit -m "feat(exchange): enable durable trusted market policy"
```
