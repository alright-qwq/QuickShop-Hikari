# Exchange Exact Market Recovery Design

Date: 2026-07-27
Status: approved for implementation planning
Scope: Phase 2 Task 6

## Goal

After a restart, an in-process runtime reset, or a database market-version mismatch, the next
decision must be identical to the decision made without the interruption. Recovery covers the
open order book, FIFO priority, sequence allocation, reference-price history, price discovery,
and circuit-breaker escalation state.

This task does not add player commands, GUI, custody flows, or administrator recovery actions.
Those remain in Phases 3 and 4.

## Persisted Risk Metadata

Schema V2 adds two columns to `exchange_market_state`:

- `discovery_quantity`: the cumulative discovery quantity, saturated at the configured discovery
  target. It never decreases.
- `circuit_breaker_level`: the exact breaker level, restricted to 0, 1, or 2. Resuming a market
  clears `halted_until` but does not clear this level.

Every settlement writes these values in the same transaction as orders, trades, balances,
ledger journals, and the existing market state. `ReferencePriceTracker` must saturate its
cumulative counter instead of allowing unbounded growth.

For existing V1 rows, both columns are initially nullable to mean "not reconstructed yet". The
first recovery replays that market's complete trade history once, writes exact non-null values,
and thereafter uses bounded recovery. Fresh markets always insert `0` for both fields. Runtime
order processing must not accept a market whose metadata is still null.

## Snapshot Boundary

`MarketSnapshot` is loaded after acquiring the market-state row lock. The same database
transaction then obtains current reads for:

- the complete `MarketState`, including its version and V2 risk metadata;
- all `OPEN` and `PARTIALLY_FILLED` orders;
- trades in the configured reference-price window, ordered by `match_sequence`;
- the maximum persisted order and trade sequences needed for validation.

SQLite uses its existing immediate write transaction. MySQL uses locking current reads after the
market row lock, so a `REPEATABLE READ` snapshot created earlier cannot return stale orders or
trades. All settlement paths already lock the market row first, so the snapshot represents one
committed market version.

For a V1 row with null risk metadata, the snapshot additionally streams all trades in
`match_sequence` order. This unbounded path is migration-only; it is never used after the exact
metadata has been written. The reconstructed metadata is written under the same market lock,
increments the market version, and is committed before any recovered runtime is published.

## Recovery Components

`OrderBookRecoveryService` owns database orchestration. A pure recovery assembler accepts a
`MarketSnapshot`, `MarketRules`, `RiskLimits`, and recovery time, and returns `RecoveredMarket`.
`RecoveredMarket` contains:

- a rebuilt `OrderBook`;
- the persisted priority and match sequence counters;
- an exact `ReferencePriceTracker`;
- an exact `CircuitBreaker`;
- the market status and committed market version.

The counters remain the last committed values. Existing service code continues allocating with
`Math.addExact(counter, 1)`, preventing accidental double increments during recovery.

Open orders are added in ascending `prioritySequence` order. This preserves insertion order in
each same-price level and therefore price-time priority, including partially filled makers.

The reference tracker is restored from the configured base price, the persisted saturated
discovery quantity, and the recent samples. The circuit breaker is restored directly from the
persisted level and `halted_until`. V1 migration recovery derives both by replaying all trades
through the same tracker and breaker algorithms. Replay derives the breaker level; the current
persisted `halted_until` remains authoritative because an administrator may already have resumed
the market after the final historical halt.

## Runtime Publication

Startup recovery publishes a complete `RecoveredMarket` through
`PersistentOrderService.publishRecoveredState`. Publication occurs while holding the existing
per-repository, per-market coordination monitor and replaces the book, tracker, breaker, and
committed version together.

During order placement, a runtime version mismatch is rebuilt from the already locked settlement
transaction rather than from a nested transaction. Settlement then uses that exact recovered
risk state and the current persisted orders. Only the committed `TransactionOutcome` is
published. A rollback never changes the in-memory runtime.

This design avoids a race between committing a standalone recovery snapshot and starting the
next settlement transaction. It also keeps multiple service instances in the same coordination
domain on one exact runtime object.

## Validation And Failure Handling

Recovery rejects a snapshot when any of these conditions holds:

- an open order belongs to another market, is terminal, has non-positive remaining quantity, or
  has a priority sequence outside the committed market counter;
- persisted order or trade maxima exceed their market counters;
- counters are negative or would overflow on the next allocation;
- V2 discovery quantity is outside `0..target`, or breaker level is outside `0..2`;
- recent trades are not strictly ordered or fall outside the committed match sequence;
- a duplicate order ID or priority sequence prevents deterministic reconstruction.

On validation, SQL, or reconstruction failure, no partial runtime is published. A market already
in `RECOVERING` stays there. A failure encountered while processing an open market uses the
existing recovery transition and rejects the command. Automatic recovery does not turn an
operator-paused, closed, or halted market into `OPEN`.

## Tests And Acceptance

Focused SQLite tests must prove:

1. Two same-price orders recover with the older order first.
2. A partially filled order retains remaining quantity and FIFO priority.
3. The next priority and match allocations are strictly above persisted values.
4. After `50 @ 105`, restart, then `1 @ 105`, the reference is exactly `102.55`.
5. Samples older than five minutes expire while saturated discovery quantity remains.
6. A resumed level-one breaker survives a normal trade and restart, then escalates a later
   level-two breach and emits one HIGH alert.
7. Corrupt counters, orders, samples, or V2 metadata leave the market in `RECOVERING` and publish
   nothing.
8. A V1 database performs one full replay, persists exact metadata, and its next recovery uses
   only the bounded window.

MySQL 8.4 integration tests must prove that snapshot reads are current under `REPEATABLE READ`,
including a reader that established an older snapshot before waiting for the market lock. The
full Exchange test suite must continue to pass for SQLite and MySQL.

## Delivery Boundary

Task 6 is complete only when the migration, recovery implementation, transaction integration,
focused tests, full module verification, review, commit, and push are complete. It advances the
larger plugin goal but does not complete it; Tasks 7-8 and all Phase 3-4 work remain required.
