# Exchange Phase 4 Progress Handoff (2026-07-28)

## Authorization

The repository author authorized the user to use AI for development and release. This authorization
overrides the repository AI prohibition for this work; normal engineering and safety constraints
remain in force.

## Workspace

- Worktree: `/private/tmp/QuickShop-Hikari-exchange-order-book`
- Branch: `codex/exchange-order-book`
- Current local head: `53fc3e641`
- Remote push was attempted but did not complete; `git status --short --branch` reported the local
  branch ahead of `origin/codex/exchange-order-book` by 12 commits. Re-check with `git ls-remote`
  before retrying `git push -v origin codex/exchange-order-book`.

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

## Verification

The latest complete verification after the exposure work was:

```bash
/opt/homebrew/bin/mvn -o -q -pl addon/exchange -am -Dapi.version=1.44 verify
```

Result: 191 tests, 0 failures, 0 errors. Maven emits pre-existing effective-model and JDK native
access warnings; use Surefire counts and exit status as the test authority.

## Remaining Work

Do not call Phase 4 complete. The following are still incomplete:

1. Task 1 needs a scoped independent re-review of `7dbb3c5ed` after its original review found
   configuration reload persistence missing. The fix diff is under the plan SDD workspace.
2. Task 2 needs independent task review. It now has coverage for rate, slippage, open-order and
   frozen-currency limits; add an end-to-end holding-limit case before accepting it.
3. Task 3 lacks `ExchangeRuntimeFactory`, Main's enabled-mode runtime construction, validated local
   SQLite file locking, MySQL lock-loss transition to `RECOVERING`, and complete recovery wiring.
4. Task 4 lacks `/qse` executor registration, QuickShop command-manager registration/unregistration,
   TNML menu integration, and the operational command set.
5. Tasks 5-10 (market data, UI pages, operations/admin tools, observability, rollout documentation,
   performance/end-to-end acceptance) remain unimplemented.

Follow the plan task-by-task, using TDD and a fresh `gpt-5.6-sol` task reviewer where service access
is available. Do not rely on the partial Task 3/4 implementations as production wiring.
