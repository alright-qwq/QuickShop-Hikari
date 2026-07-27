# Phase 2 Task 8 Report

## Implemented

- Added real MySQL concurrency integration coverage for 32 duplicate requests and opposing cross-market trades.
- Added MySQL account/inventory no-op upserts using `ON DUPLICATE KEY UPDATE` while retaining SQLite `INSERT OR IGNORE`.
- Standardized decimal reads through `ResultSet.getString()` and `BigDecimal`.
- Added repository-backed reconciliation for ledger conservation, custody versus database liabilities, and under-reserved open orders.
- Added fee-schedule parsing for worst-case BUY reservation checks and exact SELL reservation checks.
- Added immutable `ReconciliationReport` and thin `ReconciliationService`.

## TDD Evidence

- RED: `mvn -pl addon/exchange -Dtest=MySqlRepositoryIT -Dapi.version=1.44 test` failed compilation because the MySQL fixture APIs were absent (`mysql`, request/order counters, and item credit support).
- RED: `ReconciliationServiceTest` was added before the reconciliation API and failed because `ReconciliationReport`, `ReconciliationService`, and `ExchangeRepository.reconcile()` did not exist.
- GREEN: focused reconciliation/MySQL command completed with 2 SQLite tests passing and 2 MySQL tests skipped because Docker is unavailable.
- REVIEW RED: a SQLite precision regression expected `0.01` from two large opposing entries but SQL `SUM` returned `2.0` after floating conversion.
- REVIEW GREEN: raw amount strings are now accumulated with `BigDecimal`; focused reconciliation tests passed 3/3.
- GREEN: `mvn -pl addon/exchange -Dtest=*Test,*IT -Dapi.version=1.44 verify` completed with 147 tests, 140 passed, 7 skipped, 0 failures, and 0 errors.

## Environment Limitation

This computer has no Docker CLI/runtime. Testcontainers therefore skipped the 5 existing MySQL migration tests and 2 new MySQL concurrency tests. The new tests remain enabled automatically whenever Docker is available; they were also forced once on this machine and failed at container startup with Testcontainers `IllegalStateException`, confirming the environmental limitation rather than reporting a false pass.

The duplicate-request test uses 32 independent repository/service instances sharing one database, so JVM market coordination cannot serialize the requests before they reach MySQL.

## Files Changed

- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationReport.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationService.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeRepository.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ledger/ReconciliationServiceTest.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/MySqlRepositoryIT.java`

## Concerns

- Real MySQL 8.4 concurrency execution must be rerun on a Docker-capable host before Phase 2 can be considered fully environment-verified.
