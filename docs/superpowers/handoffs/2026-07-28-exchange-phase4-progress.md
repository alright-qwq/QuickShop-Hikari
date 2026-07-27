# Exchange Phase 4 Progress Handoff (2026-07-28)

## Authorization

The repository author authorized the user to use AI for development and release. This authorization
overrides the repository AI prohibition for this work; normal engineering and safety constraints
remain in force.

## Workspace

- Worktree: `/private/tmp/QuickShop-Hikari-exchange-order-book`
- Branch: `codex/exchange-order-book`
- Current local head: `4cf4e2a96`.
- The last confirmed remote head is `169c63bd4`. Local commits `4b9a01699` and
  `4cf4e2a96` are awaiting a retry because HTTPS push attempts currently time out after
  connecting to GitHub. Do not force-push; retry a normal `git push origin
  codex/exchange-order-book` until the remote responds.

The authoritative Phase 4 plan is:

`docs/superpowers/plans/2026-07-26-exchange-04-addon-ui-operations.md`

The SDD ledger is:

`.superpowers/sdd/2026-07-26-exchange-04-addon-ui-operations/progress.md`

## Recent Phase 4 Commits

- `3e38d15b0` immutable fee schedules persisted and used by historical orders.
- `7dbb3c5ed` guarded market reload persists structural/risk/fee versions atomically.
- `5f214b5e8` transaction-level rate and market-order slippage rejection.
- `3eb97c2a8` transaction-level open-order and frozen-currency/holding exposure limits. Risk
  snapshots use non-creating JDBC reads so rejected checks do not create irrelevant balance rows.
- `8f34c3dc0` tested runtime start/close ordering; dispatcher drains before writer release.
- `a1f1e9cd9` message service replaces the `messages.yml` `<requestId>` placeholder.
- `53fc3e641` Bukkit `Player` to command-router adapter with injected menu port.
- `76d61c399` rolling OHLCV ticker and SQLite/MySQL candle persistence.
- `e8d968a72` guarded structural currency-scale reload and atomic publish coverage.
- `a2c17dfde` committed-trade market-data publication, protected book quotes/depth, idle flush and
  rollover de-duplication.
- `457e3acd0`, `809f6e753`, `bbe0f4494` runtime writer guards, lock-loss fencing, Factory/Main
  assembly, fresh-install `markets.yml` provisioning, and lock acquisition before mutable database
  bootstrap.
- `8c89a42a5` explicit preflight and transaction-snapshot risk rejections for cage, market status,
  and self trade.
- `d8dc9060d` player-update coalescing, per-trade audit feed, scheduled candle flush, order
  confirmation model, and UI refresh coordinator.
- `5ba3ab22d` deployment and recovery operations guide.
- `169c63bd4` asynchronous TNML market list, shared `/quickshop exchange` and `/qse`
  entry points, and clean command/menu lifecycle teardown.
- `4b9a01699` durable request receipt reads on a non-settlement JDBC connection, so fresh
  preflight cage rejects stay out of the market mutex while retries retain idempotency.
- `4cf4e2a96` writer-fence gate around scheduled HALTED-market recovery; lock-loss
  publication cannot interleave with a recovery transaction.

## Verification

The latest complete verification after the command, retry, and writer-fence work was:

```bash
/opt/homebrew/bin/mvn -o -q -pl addon/exchange -am -Dapi.version=1.44 verify
```

Result: full Exchange verification exited 0 at `4cf4e2a96`. Maven emits pre-existing effective-model
and JDK native access warnings; use Surefire counts and exit status as the test authority.

## Remaining Work

Do not call Phase 4 complete. The following are still incomplete:

1. Task 1 is complete: its scoped re-review found no remaining issues after `e8d968a72`.
2. Task 2 preflight retry remediation is in `4b9a01699`; it needs a final scoped review of the
   separate read-only receipt lookup and decorator fallback.
3. Task 3 scheduled HALTED recovery fencing is in `4cf4e2a96`; it needs a final scoped review of
   `SingleWriterGuard.runWhileHeld` and MySQL lock-loss linearization.
4. Task 4 has both `/qse` and QuickShop command-manager registration/unregistration, but its
   operational command set remains incomplete.
5. Task 5 now constructs market data in the production factory, passes it to persistent order
   services, schedules minute flushes, and offers per-trade audit plus coalesced player update
   APIs. It still needs a scoped re-review and actual UI/player callback registration.
6. Tasks 6-7 have `OrderConfirmation`, `GuiRefreshCoordinator`, production read-only market views,
   and a TNML market-list page. Detail/order/account/admin pages, player-close subscriptions and
   fixed-request-id confirmation submission remain unimplemented.
7. Task 8 administration/audit/export/reconciliation and Task 9 metrics/alert persistence remain
   unimplemented. Task 10 has the operations guide only; end-to-end and load acceptance are absent.

Follow the plan task-by-task, using TDD and a fresh `gpt-5.6-sol` task reviewer where service access
is available. Do not rely on the partial Task 3/4 implementations as production wiring.
