# Phase 2 Task 7 Report: Atomic Settlement Rollback

## Status

Completed in the `exchange-order-book` worktree. All seven settlement persistence boundaries can
be fault-injected inside the repository transaction. Every injected failure rolls back the complete
settlement, moves the affected market to `RECOVERING` in an independent transaction, calls the
recovery handler once, and leaves the in-memory runtime unpublished.

## Changes

- Added `SettlementStage` with seven exact boundaries: reservation, balance updates, maker updates,
  taker order insert, trade inserts, ledger inserts, and request-result persistence.
- Added `SettlementObserver` with a production-safe `NONE` default and constructor wiring that keeps
  existing callers unchanged.
- Split balance/maker and trade/journal persistence loops so each observer boundary represents one
  completed class of database mutation.
- Wrapped observer failures only, preserving normal business exception behavior. Rollback and
  connection-close errors suppressed on the wrapper are transferred to the original exception.
- Added complete SQLite state snapshots across accounts, inventory, orders, trades, journals,
  entries, request results, alerts, and market risk/sequence fields.
- Added per-trade journal auditing that requires exactly one currency and one item journal, exact
  buyer/seller/fee/custody roles, the expected asset, exact entry counts, and a zero sum per journal.

## TDD Evidence

Initial focused compilation failed because `SettlementStage` and `SettlementObserver` did not
exist. After the observer implementation, all seven parameterized stages passed exact rollback
checks.

Independent review then found three Important gaps. Two new tests failed before their fixes:

- the original `InjectedFailure` lost a simulated suppressed rollback error;
- an inserted trade with no journals produced no invariant violation.

The third review test constructed a level-two circuit-breaker trade, failed after request-result
persistence, and passed by proving the inserted HIGH alert rolled back with the rest of settlement.
After the fixes, the focused failure-injection and repository rollback set passed 20/20.

Final command:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
$env:TESTCONTAINERS_HOST_OVERRIDE='127.0.0.1'
& 'C:\Users\ztrnb\AppData\Local\Temp\codex-maven-3.9.11-run2\apache-maven-3.9.11\bin\mvn.cmd' `
  -pl addon/exchange '-Dtest=*Test,*IT' '-Dapi.version=1.44' verify
```

Result: `BUILD SUCCESS`; 142 tests passed, 0 failures, 0 errors, 0 skipped. Five tests ran against
MySQL 8.4.

## Review

The independent reviewer found no Critical issues. Its three Important findings covered suppressed
rollback evidence, journal completeness, and alert-table rollback coverage. Commit `6f2eb7ee8`
contains all three fixes and their regression tests.

## Next Scope

Phase 2 Task 8 must add real MySQL concurrent idempotency and opposing lock-order tests, then add
the daily ledger reconciliation report/service. Phase 2 is not complete until Task 8 passes its
full acceptance gate; the complete plugin still requires all Phase 3 custody flows and Phase 4
server wiring, commands, GUI, market data, operations, and Paper/Folia acceptance.
