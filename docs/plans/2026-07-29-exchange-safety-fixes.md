# Exchange Safety Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate the confirmed Exchange asset-safety and lifecycle defects, starting with database writer fencing and machine-verified item-transfer review resolution.

**Architecture:** MySQL retains its advisory lock for leader election, while a durable writer-epoch row provides transaction-level fencing. Every JDBC mutation transaction locks and validates that row before running domain work. Item review resolution becomes an asynchronous two-phase operation: inspect inventory markers on the player entity scheduler, then enter the runtime writer fence and settle only from machine-verifiable evidence.

**Tech Stack:** Java 21, Maven, JDBC, MySQL/SQLite, Paper/Folia entity scheduling, JUnit 5, AssertJ.

---

### Task 1: Add durable writer-epoch schema

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/SchemaV4.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/TableNames.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunner.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/MigrationRunnerTest.java`

**Steps:**
1. Add a failing migration test expecting the writer-epoch table and schema version 4.
2. Run only `MigrationRunnerTest` and confirm the expected table/version failure.
3. Add one-row writer epoch schema with a stable primary key and non-negative epoch.
4. Run `MigrationRunnerTest` and confirm it passes.

### Task 2: Fence every JDBC mutation transaction

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/TransactionFence.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/MySqlWriterEpoch.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/MySqlSingleWriterGuard.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcTransactionFenceTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/MySqlSingleWriterGuardTest.java`

**Steps:**
1. Add a failing repository test proving transaction work is rejected before execution when its token is stale.
2. Add a failing ordering test proving the fence row is locked before domain work and remains locked until commit.
3. Add a failing guard test proving epoch activation is performed on the advisory-lock connection and requires current lock ownership.
4. Implement a no-op SQLite fence and a MySQL fence that executes `SELECT epoch ... FOR UPDATE` and compares the held token.
5. Activate a new epoch on the advisory-lock connection after schema migration and before other startup mutations.
6. Pass the active fence into every repository instance and fence startup market registration.
7. Run the focused persistence/runtime tests, then the Exchange module test suite.

### Task 3: Require marker inspection before item-withdrawal failure resolution

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/TransferReviewCoordinator.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/TransferReviewCoordinatorTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeServiceTest.java`

**Steps:**
1. Replace the unsafe test that accepts free-text item-withdrawal failure with a failing test that rejects direct settlement.
2. Add coordinator tests: marker count zero resolves failure; positive marker count remains in review; inventory failure/offline remains in review; duplicate request returns the original terminal transfer.
3. Implement asynchronous marker inspection through `InventoryGateway.markedQuantity()`.
4. Produce structured evidence containing transfer ID, observed marker count and observation outcome; do not trust operator text for custody facts.
5. Re-enter the runtime writer fence only after observation and perform the database settlement there.
6. Update the admin command path to handle asynchronous completion without blocking the Bukkit/player thread.
7. Run focused review, command and recovery tests.

### Task 4: Complete safe marker terminal paths

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/TransferReviewCoordinator.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryService.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/TransferReviewCoordinatorTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryServiceTest.java`

**Steps:**
1. Add failing tests for item-withdrawal success after verified marker cleanup and item-deposit failure after verified marker cleanup.
2. Implement cleanup-result checks without changing custody state on unknown outcomes.
3. Preserve `REVIEW_REQUIRED` for offline, partial, inconsistent or failed cleanup.
4. Run all transfer tests.

### Task 5: Fix shutdown and account executor lifecycle

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/PlayerOperationSerialiser.java`
- Test: corresponding runtime and transfer tests.

**Steps:**
1. Add failing tests for menu-close failure still invoking runtime close and for retrying partially failed shutdown.
2. Implement an idempotent phase-tracking shutdown state machine with aggregated exceptions.
3. Retain the runtime reference while safety-critical cleanup remains incomplete.
4. Replace per-account executors with a bounded shared executor plus per-account future chains.
5. Add concurrency tests for same-account ordering, cross-account progress, rejection and idle-chain removal.

### Task 6: Fix diagnostics, startup rollback and idempotency defects

**Files:**
- Modify relevant runtime, command, service and operations classes identified in the final review.
- Test corresponding focused test classes.

**Steps:**
1. Add failing multi-market cancellation test preserving an earlier `SQLException`.
2. Add failing reconcile test requiring the original report payload on duplicate request ID.
3. Add startup-failure tests proving already-created executors and schedulers are closed in reverse order.
4. Add structured logging for GUI submissions, admin commands, maintenance and login recovery.
5. Fix the cross-platform audit-path test using platform-native absolute paths.

### Task 7: Verify and review

**Steps:**
1. Run all focused tests for each completed task.
2. Run `mvn -pl addon/exchange -am -Dapi.version=1.44 verify` with JDK 21.
3. Inspect `git diff --check` and the full diff.
4. Request an independent code review against the final review report.
5. Fix all Critical/Important findings and rerun the full verification.
