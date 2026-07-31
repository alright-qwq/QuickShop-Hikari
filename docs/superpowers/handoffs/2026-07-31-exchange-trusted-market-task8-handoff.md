# Exchange Trusted Market Task 8 Handoff (2026-07-31)

## Workspace

- Repository: `QuickShop-Hikari`
- Worktree: `C:\Users\ztrnb\Documents\QuickShop-Hikari\.worktrees\exchange-safety-review`
- Branch: `fix/exchange-safety`
- Pre-handoff HEAD: `c064cb127` (`feat(exchange): recover trusted market influence`)
- Compatibility baseline: QuickShop-Hikari `6.2.0.11`
- Implementation plan: `docs/superpowers/plans/2026-07-31-exchange-trusted-market-core.md`
- Current milestone: Task 8, settlement/risk/quote/breaker integration

This file is committed together with the Task 8 implementation. The commit containing this file is
the handoff commit; use `git log -1 --oneline` after pulling to obtain its final hash.

## Completed in Task 8

- Trusted influence, trusted state, raw one-minute Candle, ordinary Trade, orders, balances,
  journals and market state are persisted in the same settlement transaction.
- Quote reference, price cage, market-order slippage and breaker input now use the trusted price.
- `MarketQuote.lastPrice` remains the raw last execution; `referencePrice` is the trusted price and
  the quote now exposes `LiquidityTier`. The old constructor remains for source compatibility.
- Repository-backed market data uses `acceptCommitted(...)`, avoiding a duplicate in-memory Candle
  after the database transaction has already stored the raw Candle.
- Restart in the same minute no longer replaces the persisted aggregate with a partial in-memory
  Candle, and markets inactive for more than 24 hours recover raw `lastPrice` through
  `latestCandle(...)`.
- SQLite Candle aggregation now merges Java `BigDecimal` values exactly instead of performing
  lossy SQLite numeric arithmetic.
- The circuit breaker compares the trusted price immediately before and after each accepted
  influence, so accumulated drift from previously accepted trades is not mistaken for one large
  move.
- `OrderBookRecoveryService` receives the configured discovery quantity; online settlement and
  replay therefore use the same lot/factor calculation.
- `AFTER_RISK_UPDATE` failure injection occurs after trusted influence/state, Candle and market
  state writes. The integration test proves the whole settlement rolls back if a failure occurs
  there.
- The old `ReferencePriceTracker` remains because production recovery still calls it. Do not delete
  it until those callers are migrated.

## Review fixes included

An independent review found and this commit fixes:

1. raw `lastPrice` loss after more than 24 hours without a trade;
2. SQLite precision loss above IEEE-754 integer precision;
3. breaker decisions based on cumulative trusted/guidance drift;
4. replay hard-coding discovery quantity `100`;
5. a rollback test that injected failure before the Candle write.

No remaining Critical/Important issue was found in the final local review.

## Fresh verification

Executed immediately before handoff:

```powershell
& 'C:\Users\ztrnb\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' `
  -o -f pom.xml -pl addon/exchange -am -rf :exchange `
  '-Dapi.version=1.44' `
  '-Dtest=TrustedPriceEngineTest,TrustedSettlementIntegrationTest,TrustedMarketRecoveryTest,JdbcTrustedMarketTest,OrderBookRecoveryServiceTest,PersistentOrderServiceTest,MarketDataServiceTest,SettlementFailureInjectionTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: `Tests run: 87, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

Also verified:

- `git diff --cached --check`: clean;
- staged content contains none of `TransferMarker`, `claimReviewedTransfer`,
  `resolveClaimedTransfer`, `liveCandles`, `MarketDisplay`, `ExchangeHandbook` or
  `setLockEmptySlots`;
- direct `customName(...)` calls remain confined to `ItemStackCompat`, which provides the
  `customName -> display` fallback required by QSH 6.2.0.11;
- no new-only TNML call or change to the `ExchangeMenuNavigator.SWITCHING` protocol is part of
  this commit.

Maven still prints the repository's existing effective-model, deprecated API, SLF4J-provider and
native-access warnings. They did not produce a compile or test failure.

## Important worktree warning

The worktree intentionally remains very dirty after this commit. It contains large, unfinished
user-owned changes for transfer-review claims/markers, market displays/charts, handbook/UI work,
configuration and documentation. Some Task 8 files also contain excluded unstaged hunks:

- `MarketDataService.java` / `MarketDataServiceTest.java`: live Candle display API/tests;
- `ExchangeRuntimeFactory.java`: display, marker, recovery and view-executor wiring;
- `ExchangeTransaction.java` and `JdbcExchangeRepository.java`: reviewed-transfer claim methods.

Do not run `git reset --hard`, `git clean`, broad checkout/restore, stash-pop automation, or stage
all files. Start by reading `git status --short` and inspecting both staged/unstaged sides of mixed
files. The Task 8 commit was built with hunk-level staging specifically to preserve these changes.

## Next work

Continue with Task 9 of the trusted-market plan:

1. add versioned `trusted-market` configuration values;
2. schedule durable no-trade anchor reversion through the existing single writer;
3. publish in-memory state only after the adjustment/state transaction commits;
4. add restart coverage proving maintenance adjustments persist without creating Trade/Candle
   volume;
5. run the targeted trusted-market suite, full Exchange reactor verification and the QSH 6.2.0.11
   compatibility boundary check.

Before Task 9, pull this branch on the next computer, read this file and the trusted-market plan,
then inspect the remaining worktree changes instead of assuming they belong to Task 9.
