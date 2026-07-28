# Exchange Phase 4 Progress Handoff (2026-07-28)

## Workspace

- Worktree: `C:\Users\ztrnb\Documents\QuickShop-Hikari\.worktrees\exchange-order-book`
- Branch: `codex/exchange-order-book`
- Current HEAD: `d90e69a72c81dcf65587316a90b684e1adfba50d`
- State: substantial uncommitted WIP; do not reset, commit, or push without explicit user approval.
- Preserve the pre-existing deletions of `AGENTS.md` and `EULA.md`; do not restore or include them accidentally.

The authoritative plan remains:

`docs/superpowers/plans/2026-07-26-exchange-04-addon-ui-operations.md`

## Whitelist MVP Implemented

- Production runtime wiring, SQLite file lock and MySQL advisory writer lock.
- Writer-fenced database bootstrap, startup recovery, login recovery, player/admin mutations, maintenance/final candle flush, and bounded drain for recovery, player custody, and GUI submission.
- Lock loss now applies a strictly local write fence; an instance with untrusted ownership performs no further database mutation. Final flush failure retains writer ownership rather than reporting a successful shutdown.
- Limit GTC and protected market IOC orders, cancellation, currency/item deposits and withdrawals.
- `/qse`, `/quickshop exchange`, rollout whitelist, precise player/admin permissions.
- TNML market/detail/order/assets/history/confirmation pages with generation-safe async updates,
  single-use confirmation, identity/permission/rollout rechecks, and zh-CN/en-US messages.
- Folia-sensitive GUI shutdown closes inventories through each player's entity scheduler and does not fall back to unsafe cross-thread inventory access if scheduling is rejected.
- Account/market risk, candles/ticker, transaction settlement, durable idempotency, double-entry ledger.
- Admin market pause/resume, force-cancel, audit CSV export, and evidence-based
  `REVIEW_REQUIRED` transfer resolution.
- Reconciliation now runs behind the runtime writer fence. The report, affected-market CAS pause,
  HIGH `RECONCILIATION_DIFFERENCE` alert, append-only `RECONCILIATION_AUTO_PAUSE` audit, and
  request receipt are one database transaction. Currency differences map to all markets using the
  currency; item differences map to the market; under-reserved or unmapped differences conservatively
  pause all configured markets.

## Latest Verification

Final security-focused regressions cover runtime writer ownership, login recovery, lock-loss fencing,
Folia GUI shutdown, and final-confirmation rollout checks.

Complete Exchange module:

```text
Tests run: 321, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Complete dependency reactor:

```bash
mvn.cmd -o -f <worktree>\pom.xml -pl addon/exchange -am -Dapi.version=1.44 verify
```

Result: all 7 reactor modules succeeded; Exchange ran 321/321 tests and produced the shaded addon.
The online command previously stalled at `Scanning for projects...`; root non-recursive validation
and offline reactor validation both complete immediately, so the stall is remote repository/model
metadata access rather than a source or POM failure. Use offline verification when the dependency
cache is complete.

`git diff --check` exits 0. Windows reports LF-to-CRLF conversion warnings only.

Final addon artifact:

`addon/exchange/target/Addon-Exchange-6.3.0.0-SNAPSHOT-11.jar`

The JAR was rechecked after the final reactor build: 453,383 bytes, modified
`2026-07-28T21:32:10+08:00`. It contains `plugin.yml`, `config.yml`, `markets.yml`, `messages.yml`,
and the persistence schema/migration classes (`SchemaV1`, `SchemaV2`, `SchemaV3`,
`MigrationRunner`).

## Release Position

This is an automated-test-complete whitelist MVP release candidate, not yet an unattended public
production release. Before real-money/economy rollout, execute the checklist in
`docs/exchange-operations.md` on actual Paper and Folia servers, and complete one real SQLite and one
real MySQL end-to-end cycle. Also verify MySQL second-writer rejection, SQLite file locking,
restart/recovery, full-inventory withdrawal, plugin disable during custody activity, and an injected
reconciliation difference.

Phase 4 full scope still has non-MVP gaps: comprehensive metrics snapshots, abnormal-trading
detection, dedicated end-to-end IT, and load IT. These do not invalidate a tightly controlled
whitelist RC, but they remain blockers for broad public rollout.

## Next Steps

1. Perform Paper/Folia and SQLite/MySQL manual acceptance from `docs/exchange-operations.md`.
2. Resolve any environment findings and rerun the offline reactor verification.
3. Review the complete uncommitted diff and explicitly exclude `AGENTS.md`/`EULA.md` from any commit.
4. Only after user approval, create a focused commit and push normally; never force-push.
