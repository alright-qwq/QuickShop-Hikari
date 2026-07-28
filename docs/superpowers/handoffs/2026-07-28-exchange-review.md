# Exchange Whitelist MVP Review (2026-07-28)

## Scope And Status

This is a read-only review of the uncommitted Exchange whitelist-MVP worktree.
It is not an approval for public or unattended production deployment.

The implementation and handoff correctly describe the current state as a
whitelist release candidate. It still lacks comprehensive metrics snapshots,
abnormal-trading detection, dedicated end-to-end and load tests, and real
Paper/Folia plus SQLite/MySQL acceptance runs.

## Blocking Findings

### P0: MySQL writer ownership is not a database fencing guarantee

`MySqlSingleWriterGuard` checks the dedicated `GET_LOCK` connection only when
guarded work starts. Its connection monitor needs the write lock, while
guarded work holds the read lock. If the lock connection is lost during a
long-running operation, MySQL releases the advisory lock and another server
can acquire it, while this instance can still commit through a separate
repository connection.

Affected code:

- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/MySqlSingleWriterGuard.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`

Use a persistent fencing epoch/token validated atomically in every mutation
transaction. A process-local read/write lock and a JDBC connection health
check cannot prevent this split-brain write window.

### P0: Manual review can release a possibly delivered item withdrawal

`AdminExchangeService.resolveReview` releases frozen items when an
`ITEM_WITHDRAWAL` is marked as external failure, without verifying that no
marker-bearing delivery exists in the player inventory. A successful item
delivery followed by a database failure or crash can therefore leave the
player with the physical items and restore their internal balance.

Affected code:

- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeService.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/ItemTransferService.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGateway.java`

Add an entity-scheduled, evidence-producing marker reconciliation operation.
Only release frozen items after it proves that the delivery did not occur.

## Important Findings

### P1: A submitter drain failure skips runtime shutdown

`Main.onDisable` closes the menu service before entering the `try` that closes
the runtime. `ExchangeMenuService.close` may throw if its submit executor
times out, so the writer lock, maintenance task, recovery executors, and
player-operation executors can remain alive. A plugin reload can then fail to
obtain writer ownership.

Make shutdown best-effort with independent `try/finally` blocks, aggregate
errors for logging, and always attempt runtime shutdown.

### P1: Runtime shutdown failures cannot be retried

`ExchangeRuntime.close` does not use a `finally` around
`afterDispatcherClosed`. Any drain or final-flush failure leaves the writer
held. `Main` then discards the runtime reference during disable, so there is
no remaining path to retry cleanup.

Retain a fenced, retryable close state or complete a deliberate durable
recovery transition before releasing ownership. Do not rely on process exit to
release resources.

### P1: Player transfer serialisation leaks one platform thread per player

`PlayerOperationSerialiser` creates and retains a one-thread executor for each
account that has ever submitted a transfer. Idle executors are never removed
until plugin shutdown. On a large long-running server this grows without
bound.

Replace the per-player executor map with a shared executor plus per-account
future chains or a bounded/idle-reaping striped executor design.

### P1: Some item review states have no safe completion path

Recovery returns `REVIEW_REQUIRED` transfers unchanged. The review service
rejects item-deposit failure and item-withdrawal success pending marker cleanup,
but does not expose a cleanup or verification operation. Those transfers can
remain indefinitely frozen or unresolved.

Add an audited marker verification/cleanup command backed by the player entity
scheduler, then allow only the corresponding safe terminal transition.

## Other Findings

- Login recovery drops asynchronous failures without logging, making stuck
  custody operations invisible to operators.
- Reusing a reconciliation request ID recalculates a new report rather than
  returning the persisted original report, breaking result idempotency.
- `AGENTS.md` and `EULA.md` are unrelated tracked-file deletions in the working
  tree. They are intentionally excluded from this feature commit and require a
  separate explicit decision.

## Verification Evidence

The offline reactor command below completed successfully on 2026-07-28:

```powershell
mvn.cmd -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 verify
```

All seven reactor modules succeeded. The Exchange module reported 321 tests,
zero failures, zero errors, and zero skipped tests. `git diff --check` also
completed without whitespace errors. These checks do not exercise real
Paper/Folia scheduling, real MySQL advisory-lock loss, or the manual custody
review paths described above.
