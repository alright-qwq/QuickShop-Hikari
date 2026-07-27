# Task 1 Report

## Status

Complete. Commit SHA: `HEAD` (the final commit containing this report).

## Files Changed

- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketDefinition.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistry.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingEngine.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/FeeRates.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/MarketFeeSchedule.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderService.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistryTest.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/ExchangeServiceFixture.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/service/PersistentOrderServiceTest.java`

## Red/Green Evidence

- Red: `PersistentOrderServiceTest#restartedServiceUsesEachOrdersPersistedFeeVersion` failed with expected taker fee `2.00` but actual `0.20`, proving settlement used the mutable constructor rate.
- Green: the same test passed after schedule lookup and per-order version resolution; it also verifies maker version 1, taker version 2, and persisted structural version 7 after a restarted service.
- Red: `MarketRegistryTest#feeReloadAppendsAnImmutableVersion` initially failed to compile because the immutable schedule API did not exist; it passed after version history was added.
- Red: `MarketRegistryTest#rejectsTickSizeBeyondConfiguredPriceScale` failed because the invalid tick size was accepted; it passed after scale validation was added.
- Red: `PersistentOrderServiceTest#refusesToArchiveFeeVersionReferencedByOpenOrder` initially failed to compile because no archive API existed; it passed after the guarded archive implementation was added.

## Verification

- `mvn -pl addon/exchange -Dtest=MarketRegistryTest,PersistentOrderServiceTest#restartedServiceUsesEachOrdersPersistedFeeVersion+refusesToArchiveFeeVersionReferencedByOpenOrder test` - PASS, 7 tests.
- `mvn -pl addon/exchange test` - PASS, 185 tests.
- `mvn -pl addon/exchange -am test` - PASS, all 7 reactor projects; Exchange reports 185 tests.
- `git diff --check` - PASS.

## Requirements Coverage

- Existing default resource loading, strict-item validation, guarded structural reload, and confirmed risk defaults remain covered by `MarketRegistryTest`.
- Market versions now begin at 1; a fee reload appends an immutable rate entry and advances only the active fee version.
- `fee_schedule_payload` supports a version map with active version and currency scale. Legacy single-rate payloads are read as version 1 for compatibility.
- Persisted schedule replacement rejects any alteration or removal of prior versions. Retired versions can be archived only after no OPEN or PARTIALLY_FILLED order references them.
- New orders persist the database structural version and active fee version. Settlement, reservation, release, reconciliation, and matching select the stored order fee version, including after service restart.
- Tick size and price bounds are checked against `priceScale`.

## Concerns

- Maven emits pre-existing effective-model and JDK/native-access warnings. They do not fail compilation or tests.
