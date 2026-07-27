# Phase 2 Task 6 Report: Exact Market Recovery

## Status

Completed in the `exchange-order-book` worktree. The implementation restores the order book,
sequence counters, reference-price history, discovery progress, and circuit-breaker state exactly
after restart or runtime version mismatch. Independent review found no Critical issues; all four
Important issues were fixed and the re-review declared the task ready to merge.

## Changes

- Added schema V2 with nullable migration markers for `discovery_quantity` and
  `circuit_breaker_level`; fresh markets initialize both values to zero.
- Added exact, validated restoration for reference-price samples, saturated discovery quantity,
  breaker level, and halt expiry.
- Added locked market snapshots containing open orders, recent trades, and committed sequence
  maxima. MySQL uses locking current reads under `REPEATABLE READ`.
- Added one-time V1 trade-history replay and atomic metadata write-back. Connector/J uses its
  driver-level streaming mode, so replay memory does not grow with the complete trade history.
- Added exact FIFO order-book reconstruction and corruption rejection that transitions an open
  market to `RECOVERING`.
- Integrated startup recovery and transaction-bound version-mismatch recovery with atomic
  post-commit runtime publication.
- Added real MySQL tests for current snapshot reads, partially applied V2 DDL retry, and unbuffered
  history streaming.

## TDD Evidence

Focused tests were introduced before each implementation slice. Initial runs failed because V2
columns, exact risk restoration, snapshot/recovery types, and transaction-bound recovery did not
exist. The restart regression also reproduced reference price `102.53` instead of exact `102.55`.

The final review fix added a real MySQL streaming test. Before the fix it failed because a second
query on the same Connector/J session succeeded while the visitor was active, proving the entire
result had already been buffered. After setting the MySQL fetch size to Connector/J streaming
mode, the driver rejected that second query while the result stream was open and the test passed.

Focused recovery result:

```text
OrderBookRecoveryServiceTest: 8 tests, 0 failures, 0 errors, 0 skipped
```

Final command:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
$env:TESTCONTAINERS_HOST_OVERRIDE='127.0.0.1'
& 'C:\Users\ztrnb\AppData\Local\Temp\codex-maven-3.9.11-run2\apache-maven-3.9.11\bin\mvn.cmd' `
  -pl addon/exchange '-Dtest=*Test,*IT' '-Dapi.version=1.44' verify
```

Result: `BUILD SUCCESS`; 132 tests passed, 0 failures, 0 errors, 0 skipped. Five tests ran against
MySQL 8.4.

## Independent Review

The first review reported four Important issues:

- the V1 visitor API did not enable true Connector/J streaming;
- the V1 test did not prove a second recovery avoids full-history replay;
- corruption and post-recovery sequence-allocation acceptance coverage was incomplete;
- MySQL retry coverage did not simulate one implicitly committed V2 column.

Commit `c6a9524ad` fixed all four. The same reviewer re-inspected the fix, found no Critical or
Important issues, and marked it ready to merge.

## Remaining Minor Follow-ups

- Add explicit corruption cases for breaker level outside `0..2` and duplicate priorities across
  distinct order IDs. The implementation validates both; current tests cover adjacent metadata
  and duplicate-order branches.
- Extract validation and reconstruction into the pure package-private snapshot assembler described
  by the design. Current orchestration is correct but less isolated for focused tests.
- Task 7 must add the seven settlement-stage failure-injection points and strengthen per-journal
  account-role and asset-conservation assertions.

Task 6 advances the Exchange plugin but does not finish it. Phase 2 Tasks 7-8 and all Phase 3-4
integration, custody, commands, GUI, market-data, operations, and Paper/Folia acceptance work remain.
