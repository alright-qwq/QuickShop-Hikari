# Exchange Administrator Hot-Update GUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let authorized administrators inspect and change guidance price, trusted policy, risk, fees, status, displays, and affected orders through an auditable GUI without stopping the server.

**Architecture:** Put validation, impact preview, persistence-first update, runtime atomic swap, and audit in a Bukkit-free `MarketAdministrationService`. The service is synchronous and must run inside the existing fenced Exchange writer; commands and TNML pages reach it through the same asynchronous request submitter, so GUI operations cannot bypass safety gates. Reuse the existing versioned market configuration boundary and QSH 6.2-compatible page/icon/navigation wrappers.

**Tech Stack:** Java 21, CompletableFuture, existing Exchange writer fence, JDBC, Bukkit/Paper/Folia, TNML compatible with QSH 6.2.0.11, Adventure, JUnit 5, MockBukkit

---

## File map

- `operations/MarketAdministrationService.java`: previews and applies versioned mutations.
- `operations/MarketRuntimeSnapshot.java`: immutable live configuration/state read model.
- `operations/MarketRuntimeStore.java`: versioned compare-and-set publication boundary.
- `operations/MarketChangeResult.java`: applied snapshot and audit/result identity.
- `operations/MarketChangeRequest.java`: typed mutation payload and reason.
- `operations/MarketChangePreview.java`: old/new values, risk, affected orders, confirmation requirement.
- `operations/MarketConfigurationStore.java`: persistence-first configuration snapshot boundary.
- `ui/AdminMarketListPage.java`: market selector.
- `ui/AdminMarketOverviewPage.java`: trusted/raw price, tier, budgets, state.
- `ui/AdminMarketSettingsPage.java`: price, risk, fees, display controls.
- `ui/AdminMarketOrdersPage.java`: affected open orders and cancellation entry.
- `ui/AdminMarketAuditPage.java`: bounded audit history.
- `ui/AdminValuePrompt.java`: chat value/reason capture.
- `ui/AdminChangeConfirmationPage.java`: preview and second confirmation.

### Task 1: Typed previews and safety classification

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangeRequest.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangePreview.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangeValidator.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangeValidatorTest.java`

- [ ] **Step 1: Write failing preview tests**

```java
@Test
void guidancePreviewCountsOrdersOutsideNewCage() {
  MarketChangePreview preview = validator.preview(snapshot(),
      new SetGuidancePrice(MARKET, bd("130.00"), "event economy rebalance"),
      List.of(buyAt("90.00"), sellAt("150.00")));
  assertThat(preview.oldValue()).isEqualTo("100.00");
  assertThat(preview.newValue()).isEqualTo("130.00");
  assertThat(preview.affectedOrderIds()).containsExactly(sellOrderId);
  assertThat(preview.confirmation()).isEqualTo(Confirmation.DANGEROUS);
}

@Test
void structuralChangeIsBlockedUntilPausedAndEmpty() {
  assertThat(validator.preview(openSnapshot(), changeTickSize("0.10"), openOrders()).allowed())
      .isFalse();
}
```

- [ ] **Step 2: Verify RED and implement sealed requests**

```java
public sealed interface MarketChangeRequest permits SetGuidancePrice, ReanchorTrustedPrice,
    SetMarketStatus, SetRiskValue, SetFeeRates, SetDisplayValue, SetStructuralValue {
  String marketId();
  String reason();
}

public record MarketChangePreview(
    MarketChangeRequest request, boolean allowed, Confirmation confirmation,
    String oldValue, String newValue, List<UUID> affectedOrderIds,
    List<String> warnings, long expectedStructuralVersion,
    long expectedRiskVersion, long expectedTrustedPolicyVersion) { }
```

Require reason length 3–256, permission-independent domain validation, min/max/tick precision, ratio/window bounds from the trusted policy, and explicit `SAFE`, `DANGEROUS`, or `BLOCKED` classification. Guidance-only changes do not alter trusted/raw price; reanchor requires `PAUSED` and dangerous confirmation.

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChange* addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangeValidatorTest.java
git commit -m "feat(exchange): preview administrator market changes"
```

### Task 2: Persistence-first atomic configuration service

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketAdministrationService.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketRuntimeSnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketRuntimeStore.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketChangeResult.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AuditRecord.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketConfigurationPersistence.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistry.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/MarketAdministrationServiceTest.java`

- [ ] **Step 1: Write failing atomicity and stale-preview tests**

```java
@Test
void persistenceFailureLeavesRuntimeSnapshotUnchanged() {
  store.failNextWrite();
  assertThatThrownBy(() -> service.apply(actorId, requestId, approvedPreview))
      .hasMessage("forced persistence failure");
  assertThat(runtime.snapshot(MARKET)).isEqualTo(before);
  assertThat(audits).isEmpty();
}

@Test
void stalePreviewCannotOverwriteNewerRiskVersion() {
  service.apply(actorId, requestId, firstPreview);
  assertThatThrownBy(() -> service.apply(actorId, secondRequestId, firstPreview))
      .isInstanceOf(ConcurrentModificationException.class);
}
```

- [ ] **Step 2: Extend durable configuration state**

Add trusted policy version/payload, risk payload, and display payload to `MarketConfigurationPersistence.State`:

```java
record State(long structuralVersion, long riskVersion, long trustedPolicyVersion,
             long activeFeeVersion, int currencyScale, Map<Long, FeeRates> feeVersions,
             String riskPayload, String trustedPolicyPayload, String displayPayload) { }
```

Preserve immutable fee-version history. Add transaction methods to read/update this state with expected structural/risk/trusted versions. JDBC `UPDATE` includes all three expected versions in the `WHERE` clause; one affected row is success, zero is stale.

```java
MarketConfigurationPersistence.State marketConfiguration(String marketId) throws SQLException;
void updateMarketConfiguration(MarketConfigurationPersistence.State replacement,
                               MarketConfigurationPersistence.State expected)
    throws SQLException;
```

`MarketRuntimeSnapshot.apply(request)` returns a fully validated immutable replacement and increments exactly the version owned by that request. `MarketRuntimeStore` exposes `require`, `compareAndSet`, and `installCommitted`. Add `AuditRecord.marketChange(...)` as the sole encoder for before/after payloads and request identity.

- [ ] **Step 3: Implement one asynchronous writer path**

```java
public MarketChangeResult apply(UUID actorId, UUID requestId, MarketChangePreview preview)
    throws SQLException {
  MarketRuntimeSnapshot next = repository.inTransaction(tx -> {
    MarketRuntimeSnapshot current = runtimeStore.require(preview.request().marketId());
    validator.requireStillValid(preview, current, tx.openOrders(current.marketId()));
    MarketRuntimeSnapshot next = current.apply(preview.request());
    tx.updateMarketConfiguration(next.configuration(), current.configuration());
    tx.appendAudit(AuditRecord.marketChange(
        actorId, requestId, preview.request().marketId(), preview.request().reason(),
        current.auditPayload(), next.auditPayload(), clock.instant()));
    return next;
  });
  if (!runtimeStore.compareAndSet(
      next.marketId(), preview.expectedRuntimeVersion(), next)) {
    runtimeStore.installCommitted(repository.inTransaction(tx ->
        MarketRuntimeSnapshot.load(tx, next.marketId())));
  }
  return MarketChangeResult.applied(next);
}
```

The actual runtime snapshot must not change before transaction commit. If compare-and-set unexpectedly fails after commit, fence new market writes, reload the committed snapshot, then resume; never revert committed DB state with an in-memory guess.

- [ ] **Step 4: Run service tests GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/MarketAdministrationServiceTest.java
git commit -m "feat(exchange): apply market changes without restart"
```

### Task 3: Guidance price and trusted reanchor operations

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MarketAdministrationService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeTransaction.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/GuidancePriceAdministrationTest.java`

- [ ] **Step 1: Write failing semantic separation tests**

```java
@Test
void settingGuidanceDoesNotFakeTradeOrJumpTrustedPrice() {
  service.apply(adminId, requestId, approved(setGuidance("120.00")));
  assertThat(readTrustedState().guidancePrice()).isEqualByComparingTo("120.00");
  assertThat(readTrustedState().trustedPrice()).isEqualByComparingTo("100.00");
  assertThat(readMarketState().lastPrice()).isEqualByComparingTo("95.00");
  assertThat(loadTrades()).hasSize(tradesBefore);
  assertThat(loadCandles()).isEqualTo(candlesBefore);
}

@Test
void reanchorWritesAdjustmentButNoTrade() {
  pauseMarket();
  service.apply(adminId, requestId, approved(reanchor("120.00")));
  assertThat(loadAdjustments()).singleElement()
      .extracting(TrustedPriceAdjustment::type).isEqualTo(ADMIN_REANCHOR);
}
```

- [ ] **Step 2: Implement guidance and reanchor transaction paths**

Guidance-only updates state guidance and policy/config version, leaving trusted and last raw price unchanged. Reanchor requires paused state, checked preview version, non-empty reason, adjustment insert, trusted state update, and audit in one transaction. It consumes no trade budget and creates no Candle.

- [ ] **Step 3: Run GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/GuidancePriceAdministrationTest.java
git commit -m "feat(exchange): add audited guidance price controls"
```

### Task 4: Administrator GUI navigation and market overview

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuPage.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenu.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminPage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketListPage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketOverviewPage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketView.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketNavigationTest.java`

- [ ] **Step 1: Write failing permissions and icon tests**

Open admin page as a `market.view` operator, click Market Management, select a market, and assert overview icons show trusted price, raw last, guidance, liquidity tier, market/account/pair remaining budgets, status, and versions. A player without permission must not receive the route.

- [ ] **Step 2: Implement QSH 6.2-compatible pages**

```java
private static PlayerInstancePage adminPage(ExchangeMenuPage page, Consumer<PageOpenCallback> open) {
  PlayerInstancePage result = ExchangePlayerPage.create(page.page());
  result.setOpen(open);
  return result;
}

ExchangePageIcons.add(page, playerId,
    new IconBuilder(ItemStackCompat.of("CLOCK", title, lore)).withSlot(13)
        .withActions(new RunnableAction(click -> {
          contexts.selectAdminMarket(playerId, marketId);
          navigator.open(player, ExchangeMenuPage.ADMIN_MARKET_OVERVIEW);
        }))
        .build());
```

Use `ExchangeMenuNavigator` for every switch, `ExchangePageIcons` for all per-player icons, and `ItemStackCompat` for names. Do not call `setLockEmptySlots` or rely on async post-open icon refresh.

- [ ] **Step 3: Run GUI tests GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketNavigationTest.java
git commit -m "feat(exchange): add administrator market overview"
```

### Task 5: Chat input, reason capture, and confirmation page

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminValuePrompt.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminChangeConfirmationPage.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuContextStore.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuListener.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminValuePromptTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminChangeConfirmationPageTest.java`

- [ ] **Step 1: Write failing prompt state-machine tests**

```java
assertThat(prompt.accept(playerId, "120.00")).isEqualTo(PromptStage.REASON);
assertThat(prompt.accept(playerId, "season event rebalance")).isEqualTo(PromptStage.PREVIEW);
assertThat(contexts.adminChange(playerId).request())
    .isEqualTo(new SetGuidancePrice(MARKET, bd("120.00"), "season event rebalance"));
```

Test `cancel`, timeout, disconnect, invalid decimal/ratio/duration, stale preview, safe one-click apply, and dangerous two-click confirmation.

- [ ] **Step 2: Implement typed prompt sessions**

One player has at most one prompt session with market, field type, stage, creation time, and generation. Chat is consumed only while a session exists. Bukkit reads/writes occur on the player owner thread; service futures return through the actor completion scheduler. Confirmation lore lists old/new values, affected order count, warnings, reason, and expected versions.

- [ ] **Step 3: Run tests GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminValuePromptTest.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminChangeConfirmationPageTest.java
git commit -m "feat(exchange): confirm administrator GUI changes"
```

### Task 6: Settings, status, fees, orders, and audit pages

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketSettingsPage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketOrdersPage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketAuditPage.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeMenuRequest.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/RuntimeExchangeRequestSubmitter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeViewService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeService.java`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Modify: `addon/exchange/src/main/resources/plugin.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketOperationsTest.java`

- [ ] **Step 1: Write failing end-to-end menu operation tests**

From overview: pause and resume market; set guidance; edit one trusted tier limit; edit cage/breaker; create a new immutable fee version; change chart period; view affected orders; cancel a selected order through the audited existing service; and page through bounded audit records. Assert each permission separately.

- [ ] **Step 2: Implement setting groups and shared service routes**

Each icon builds a typed request and calls the same preview/apply service as commands. Structural fields show `BLOCKED` unless paused and empty. Order cancellation uses existing audited force-cancel logic rather than direct repository updates. Audit reads are bounded and run on the view executor.

Add an `ADMIN_MARKET_CHANGE` request variant carrying the approved preview, actor/request IDs, and required permission. `RuntimeExchangeRequestSubmitter` routes it through `ExchangeRuntime.callWhileWriting(...)` to `MarketAdministrationService.apply(...)`; the UI only observes the resulting future and never calls JDBC or the registry directly.

Permissions:

```yaml
quickshop.exchange.admin.market.view: {default: op}
quickshop.exchange.admin.market.risk: {default: op}
quickshop.exchange.admin.market.price: {default: op}
quickshop.exchange.admin.market.structure: {default: op}
quickshop.exchange.admin.market.orders: {default: op}
quickshop.exchange.admin.market.audit: {default: op}
```

- [ ] **Step 3: Run GUI operation tests GREEN and commit**

```text
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeService.java addon/exchange/src/main/resources/messages.yml addon/exchange/src/main/resources/plugin.yml addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/AdminMarketOperationsTest.java
git commit -m "feat(exchange): operate markets through administrator GUI"
```

### Task 7: Full hot-update and compatibility verification

**Files:**
- Modify: `docs/exchange-operations.md`
- Modify: `docs/exchange-addon-change-summary.md`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/AdminHotUpdateIntegrationTest.java`

- [ ] **Step 1: Add restart-free integration test**

Open a market, place orders, apply a safe risk update through the service, place another order and prove it uses the new version without recreating runtime. Simulate persistence failure and stale preview. Pause, reanchor, resume, and prove raw last/Candles are unchanged.

- [ ] **Step 2: Run targeted and full tests**

```text
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -Dtest='MarketAdministrationServiceTest,GuidancePriceAdministrationTest,Admin*Test' -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 verify
```

Expected: all tests and reactor modules pass.

- [ ] **Step 3: Verify real QSH 6.2.0.11 GUI behavior**

On the compatibility build/test server, open every admin page, edit a value, cancel, confirm, page orders/audit, drag/click empty slots, and navigate back. Expected: no `NoSuchMethodError`, no overwritten icons, no stuck Loading page, no item insertion, and no owner-thread blocking.

- [ ] **Step 4: Update operator documentation and commit**

Document permissions, safe/dangerous/blocked behavior, guidance versus raw price, failure rollback, and restart persistence.

```text
git add docs/exchange-operations.md docs/exchange-addon-change-summary.md addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/AdminHotUpdateIntegrationTest.java
git commit -m "docs(exchange): document administrator hot updates"
```
