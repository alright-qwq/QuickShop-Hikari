# Exchange Safety Fixes Handoff (2026-07-29)

## Workspace and baseline

- Repository: `QuickShop-Hikari`
- Worktree: `/Users/ztrnb/.config/superpowers/worktrees/QuickShop-Hikari/exchange-safety-fixes`
- Branch: `fix/exchange-safety`
- Baseline: `73cc63a16001349a5d245b78b11f2d4dfc9a8c01` (`origin/codex/exchange-order-book`)
- Implementation plan: `docs/plans/2026-07-29-exchange-safety-fixes.md`
- Scope: safety and lifecycle hardening for the Exchange addon after the 2026-07-28 review.

The branch was intentionally created from the latest remote Exchange commit. It does not include the divergent local commits `4d1c1ffbd` (audited market pause/resume) or `79d2e1c92` (bounded market metrics snapshots). If those changes are still desired, review and cherry-pick them separately after this branch is integrated.

## Implemented changes

### 1. Durable MySQL writer fencing

The MySQL advisory lock remains the leader-election mechanism, but it is no longer the only mutation guard.

- Added schema V4 and the single-row `<prefix>exchange_writer_epoch` table.
- Added `TransactionFence`, injected into `JdbcExchangeRepository`.
- `MySqlWriterEpoch.activate(...)` verifies that the activation connection owns the advisory lock, obtains an exclusive row lock, increments the epoch with CAS semantics, and returns a transaction fence for the activated epoch.
- Every repository mutation transaction obtains a shared row lock and verifies its epoch before executing domain work.
- Startup market registration obtains the same fence on the actual JDBC connection performing the writes.
- The activation order avoids upgrading the `MySqlSingleWriterGuard` read lock to its write lock.

Safety property: a newly activated writer waits for old in-flight fenced transactions; after activation, stale instances cannot begin another mutation transaction with an old epoch.

Key files:

- `persistence/SchemaV4.java`
- `persistence/TransactionFence.java`
- `persistence/MySqlWriterEpoch.java`
- `persistence/JdbcExchangeRepository.java`
- `runtime/MySqlSingleWriterGuard.java`
- `runtime/ExchangeRuntimeFactory.java`

### 2. Machine-verified item review and recovery

Reviewed item transfers now fail closed instead of trusting free-form operator evidence.

- Added `TransferReviewCoordinator`.
- `ITEM_WITHDRAWAL + CONFIRM_EXTERNAL_FAILURE` may release custody only after an actual marker observation returns exactly zero.
- `ITEM_WITHDRAWAL + CONFIRM_EXTERNAL_SUCCESS` and `ITEM_DEPOSIT + CONFIRM_EXTERNAL_FAILURE` require:
  1. marker quantity before cleanup equals the transfer amount exactly;
  2. an atomic `REVIEW_REQUIRED → REVIEW_PROCESSING` claim persists actor, request ID, decision, quantity and evidence;
  3. `clearMarker()` returns `SUCCESS`;
  4. marker quantity after cleanup equals zero;
  5. only then does settlement re-enter the runtime writer fence and CAS to `COMPLETED`/`FAILED`.
- Observation failures, null/negative values, partial or excessive marker quantities fail closed in `REVIEW_REQUIRED`; once claimed, cleanup uncertainty or interruption remains durably recoverable as `REVIEW_PROCESSING`.
- Recovery handles both crash windows: it retries cleanup if the claimed marker quantity still exists, or completes settlement if marker cleanup already reached zero. Any conflicting or malformed persisted claim fails closed.
- Duplicate review request IDs return the original terminal transfer without repeating inventory mutation or settlement, and the same transfer's administrator review and login recovery share one in-process serial chain.
- A registered `TransferMarkerListener` blocks marked custody from inventory/creative/drag/hopper movement, double-click collection, dropping or pickup, use/mutation/place/swap, death drops, and entity or armor-stand placement/removal. Ordinary actions remain allowed.
- Admin command completion and inventory inspection are dispatched through the player entity scheduler on Folia/Paper.

Key files:

- `operations/TransferReviewCoordinator.java`
- `operations/AdminExchangeService.java`
- `transfer/TransferRecoveryService.java`
- `platform/FoliaInventoryGateway.java`
- `command/AdminCommandRouter.java`
- `command/BukkitCommandActor.java`

### 3. Retryable shutdown and bounded account execution

- `ExchangeRuntime.close()` is now a synchronized, phased state machine: dispatcher drain, operational flush, then writer release.
- A failed phase can be retried; completed phases are not repeated, and the writer is not released before safety-critical draining/flushing succeeds.
- Added `ExchangeShutdown` so entrypoint/menu cleanup failures cannot skip runtime closure. Independent failures are retained using suppressed exceptions.
- `Main` clears its runtime reference only after `runtime.closed()` reports a complete close, preserving a retry path after failure.
- Replaced permanent per-player single-thread executors with a shared bounded worker pool plus per-account `CompletableFuture` tails.
- Operations remain strictly ordered per account, different accounts can progress concurrently, total accepted work is bounded, idle account tails are removed, and close drains accepted work.

Key files:

- `ExchangeShutdown.java`
- `Main.java`
- `runtime/ExchangeRuntime.java`
- `transfer/PlayerOperationSerialiser.java`

### 4. Idempotency, diagnostics, and startup rollback

- Reconcile duplicate requests now deserialize and return the original report payload rather than rerunning reconciliation or returning a different result.
- Cross-market cancellation preserves the first `SQLException` and attaches later SQL failures as suppressed exceptions.
- Login recovery asynchronous failures are logged with the affected account UUID.
- Runtime factory startup tracks acquired resources and closes them in reverse order; cleanup failures are attached to the startup exception.
- The audit-path test now uses a platform-native absolute path and passes on macOS.

Key files:

- `operations/AdminExchangeService.java`
- `service/ExchangeActionService.java`
- `platform/TransferLoginListener.java`
- `runtime/ExchangeRuntimeFactory.java`

## Test coverage added or expanded

Major regression suites include:

- `ExchangeShutdownTest`
- `ExchangeRuntimeTest`
- `PlayerOperationSerialiserTest`
- `TransferReviewCoordinatorTest`
- `TransferRecoveryServiceTest`
- `FoliaInventoryGatewayTest`
- `AdminCommandRouterTest`
- `JdbcTransactionFenceTest`
- `MySqlWriterEpochTest`
- `MySqlSingleWriterGuardTest`
- `ExchangeRuntimeFactoryTest`
- `TransferLoginListenerTest`
- `AdminExchangeServiceTest`
- `ExchangeActionServiceTest`

## Fresh verification

Executed with JDK 21 on the final local source, including the marker lifecycle, claimed-review
recovery, retryable drain, Paper/QuickShop-H2 configuration fixes, and the GUI redesign
(unified chrome, navigation, loading/empty/error states, confirmation back/cancel flow,
admin page localization):

```powershell
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
mvn -f "C:\Users\ztrnb\Documents\QuickShop-Hikari\.worktrees\exchange-safety-review\pom.xml" `
  -pl addon/exchange -am "-Dapi.version=1.44" verify
```

Result:

```text
Tests run: 394, Failures: 0, Errors: 0, Skipped: 0
All 7 reactor modules: SUCCESS
BUILD SUCCESS
Total time: 01:44 min
Finished at: 2026-07-29T18:50:28+08:00
```

Release JAR inspection:

```text
Addon-Exchange-6.3.0.0-SNAPSHOT-11.jar
Size: 500255 bytes
SHA-256: 5C36389D049326BE634C93E115CEDB0A35B1A1E96D8CB7F11337ADC640FE1F7B
Required resources/classes: OK
Packaged database default: mode: sqlite
messages.yml: 134 en-US keys, 134 zh-CN keys (parity confirmed)
ExchangeMenuChrome.class: present
TransferMarkerListener.class: present
```

Also executed:

```bash
git diff --check
```

Result: exit code 0, with no whitespace errors.

The Maven build still reports pre-existing model/shading warnings in other modules (duplicate dependency declarations, deprecated `LATEST`/`RELEASE`, and overlapping shaded classes/resources). No new compile or test failure was reported.

## Validation limitations and release position

This commit is suitable as a reviewed source handoff, but it is not evidence of a completed production rollout.

- Docker is not installed in the current environment (`docker: command not found`), so the real MySQL/Testcontainers fault-injection scenarios were not executed here.
- Still required before a production economy rollout:
  - two live MySQL instances competing for writer ownership;
  - advisory-lock connection loss followed by stale-writer mutation attempts;
  - delayed old transaction while a new epoch activates;
  - Paper and Folia end-to-end item transfer/review/recovery cycles;
  - plugin disable/reload during queued account work and custody operations.
- Independent automated review attempts did not return a usable result (upstream access/turn-limit failures). A manual source/diff review and the full Maven verification were completed instead.
- Existing broader Phase 4 gaps such as full load testing, abnormal-trading detection, and production metrics acceptance remain outside this safety commit.

## Recommended next steps

1. Run the MySQL fault-injection matrix in an environment with Docker/Testcontainers or real isolated MySQL servers.
2. Execute `docs/exchange-operations.md` on actual Paper and Folia servers.
3. Review whether to cherry-pick `4d1c1ffbd` and `79d2e1c92`; do not merge them blindly because this branch was intentionally based on the latest remote commit.
4. After environment acceptance, build and checksum the release addon JAR from the accepted commit.
5. Merge or cherry-pick this focused safety commit without force-pushing.
