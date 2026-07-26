# Exchange Phase 3 Custody and Transfers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 安全连接 SQL 内部交易资产与外部 EconomyProvider/玩家背包，通过持久化转账状态机处理充值、提现、背包不足、进程中断和不确定结果。

**Architecture:** TransferService 只编排状态机和仓储端口；EconomyGateway 与 InventoryGateway 是外部副作用边界。每个玩家的外部操作串行执行；SQL 可以证明的结果自动完成或回滚，跨外部调用的崩溃窗口一律进入 REVIEW_REQUIRED，绝不自动重复扣款或付款。

**Tech Stack:** Java 21、QuickShop EconomyProvider、Paper ItemStack/PersistentDataContainer、Folia 实体调度器、JDBC 仓储、JUnit Jupiter、MockBukkit 或纯端口 fake

---

## 前置条件与文件结构

先完成并验证 Phase 1 和 Phase 2 计划。

- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/model/*.java — 转账类型、状态、外部结果与不可变记录。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRepository.java — 转账创建、CAS 状态切换和查询端口。
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java — 实现转账端口并复用同一账户事务。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/PlayerOperationSerialiser.java — 每玩家串行外部调用。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyTransferService.java — 资金存取状态机。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/QuickShopEconomyGateway.java — EconomyProvider 适配器。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintService.java — 普通材料与特殊物品严格指纹。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGateway.java — 玩家实体线程上的标记、移除和发放。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/ItemTransferService.java — 物品存取状态机。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryService.java — 启动/登录恢复和 REVIEW_REQUIRED 分流。
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/**/*.java — 每个状态间故障、重复点击与恢复测试。

### Task 1: 持久化转账模型与 CAS 状态切换

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/model/TransferStatus.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/model/TransferType.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/model/ExternalResult.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/model/TransferRecord.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRepository.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/IdempotencyConflictException.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/JdbcTransferRepositoryTest.java

- [ ] **Step 1: 写合法状态转换、版本冲突和 requestId 幂等红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTransferRepositoryTest {
  @Test
  void createsOnceAndUsesCompareAndSetTransitions() throws Exception {
    TransferFixture fixture = TransferFixture.sqlite();
    UUID account = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    TransferRecord prepared = TransferRecord.prepared(UUID.randomUUID(), request, account,
        TransferType.MONEY_DEPOSIT, "USD", new BigDecimal("50.00"), Instant.EPOCH);

    TransferRecord first = fixture.repository().create(prepared);
    TransferRecord duplicate = fixture.repository().create(
        TransferRecord.prepared(UUID.randomUUID(), request, account,
            TransferType.MONEY_DEPOSIT, "USD", new BigDecimal("50.00"), Instant.EPOCH));
    TransferRecord processing = fixture.repository().transition(first.transferId(), 0,
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);

    assertThat(duplicate.transferId()).isEqualTo(first.transferId());
    assertThat(processing.version()).isEqualTo(1);
    assertThatThrownBy(() -> fixture.repository().transition(first.transferId(), 0,
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null))
        .isInstanceOf(java.util.ConcurrentModificationException.class);
  }
}
~~~

- [ ] **Step 2: 运行并确认转账类型缺失**

Run: mvn -pl addon/exchange -Dtest=JdbcTransferRepositoryTest test

Expected: FAIL，编译器报告 TransferRecord、TransferRepository 等类型不存在。

- [ ] **Step 3: 定义转账领域模型**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer.model;
public enum TransferStatus { PREPARED, PROCESSING, COMPLETED, FAILED, REVIEW_REQUIRED }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer.model;
public enum TransferType { MONEY_DEPOSIT, MONEY_WITHDRAWAL, ITEM_DEPOSIT, ITEM_WITHDRAWAL }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer.model;
public enum ExternalResult { SUCCESS, FAILURE, UNKNOWN }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferRecord(
    UUID transferId, UUID requestId, UUID accountId, TransferType type,
    String assetId, BigDecimal amount, TransferStatus status,
    String externalMarker, String failureReason,
    Instant createdAt, Instant updatedAt, long version) {

  public TransferRecord {
    if (transferId == null || requestId == null || accountId == null || type == null
        || assetId == null || assetId.isBlank() || amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("invalid transfer");
    }
  }

  public static TransferRecord prepared(UUID transferId, UUID requestId, UUID accountId,
                                        TransferType type, String assetId, BigDecimal amount,
                                        Instant now) {
    return new TransferRecord(transferId, requestId, accountId, type, assetId, amount,
        TransferStatus.PREPARED, transferId.toString(), null, now, now, 0);
  }
}
~~~

- [ ] **Step 4: 定义仓储并实现唯一创建与 CAS**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import java.sql.SQLException;
import java.util.*;

public interface TransferRepository {
  TransferRecord create(TransferRecord prepared) throws SQLException;
  Optional<TransferRecord> find(UUID transferId) throws SQLException;
  Optional<TransferRecord> findByRequest(UUID accountId, UUID requestId) throws SQLException;
  List<TransferRecord> findUnfinished(UUID accountId) throws SQLException;
  List<TransferRecord> findAllUnfinished() throws SQLException;
  TransferRecord transition(UUID transferId, long expectedVersion,
                            TransferStatus expectedStatus, TransferStatus targetStatus,
                            String reason) throws SQLException;
}
~~~

Jdbc create 在 (account_id,request_id) 唯一冲突时读取并返回原记录；若原记录的 type、assetId 或 amount 不同，则抛 IdempotencyConflictException。transition 使用：

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

public final class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("requestId already belongs to a different transfer");
  }
}
~~~

~~~java
String sql = "UPDATE " + tables.transfers()
    + " SET status=?,failure_reason=?,updated_at=?,version=version+1"
    + " WHERE transfer_id=? AND status=? AND version=?";
~~~

受影响行数不是 1 时抛 ConcurrentModificationException。自动流程的合法边为 PREPARED→PROCESSING、PREPARED→FAILED、PROCESSING→COMPLETED、PROCESSING→FAILED，以及 PREPARED/PROCESSING→REVIEW_REQUIRED；COMPLETED、FAILED 为终态。REVIEW_REQUIRED 只能由 Phase 4 的独立财务恢复权限流程在保存证据、审计记录和必要补偿 journal 的同一事务中转为 COMPLETED 或 FAILED，普通恢复器不能推进它。

- [ ] **Step 5: 运行转账仓储测试**

Run: mvn -pl addon/exchange -Dtest=JdbcTransferRepositoryTest test

Expected: PASS；重复 requestId 返回同 transferId，陈旧 version 无法推进状态。

- [ ] **Step 6: 提交转账模型与仓储**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence/JdbcExchangeRepository.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/JdbcTransferRepositoryTest.java
git commit -m "feat(exchange): persist transfer state machine"
~~~

### Task 2: 实现资金存入状态机

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/EconomyGateway.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/PlayerOperationSerialiser.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyTransferService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyDepositTest.java

- [ ] **Step 1: 写成功、明确失败、不确定结果与重复点击红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyDepositTest {
  @Test
  void debitsExternallyOnceThenCreditsInternalAccount() throws Exception {
    AtomicInteger debits = new AtomicInteger();
    TransferFixture fixture = TransferFixture.withEconomy((player, currency, amount) -> {
      debits.incrementAndGet();
      return ExternalResult.SUCCESS;
    });
    UUID request = UUID.randomUUID();

    TransferRecord first = fixture.money().deposit(request, fixture.player(), "USD",
        new BigDecimal("25.00")).join();
    TransferRecord duplicate = fixture.money().deposit(request, fixture.player(), "USD",
        new BigDecimal("25.00")).join();

    assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(duplicate.transferId()).isEqualTo(first.transferId());
    assertThat(debits).hasValue(1);
    assertThat(fixture.availableCurrency()).isEqualByComparingTo("25.00");
    assertThat(fixture.ledgerIsBalanced()).isTrue();
  }

  @Test
  void unknownDebitRequiresReviewAndDoesNotCredit() {
    TransferFixture fixture = TransferFixture.withEconomy(
        (player, currency, amount) -> ExternalResult.UNKNOWN);
    TransferRecord result = fixture.money().deposit(UUID.randomUUID(), fixture.player(), "USD",
        new BigDecimal("25.00")).join();
    assertThat(result.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.availableCurrency()).isZero();
  }
}
~~~

- [ ] **Step 2: 运行并确认资金服务缺失**

Run: mvn -pl addon/exchange -Dtest=MoneyDepositTest test

Expected: FAIL，编译器报告 EconomyGateway、MoneyTransferService 等类型不存在。

- [ ] **Step 3: 定义外部经济端口和每玩家串行器**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import java.math.BigDecimal;
import java.util.UUID;

public interface EconomyGateway {
  ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount);
  ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount);
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class PlayerOperationSerialiser implements AutoCloseable {
  private final Map<UUID, ExecutorService> executors = new ConcurrentHashMap<>();

  public <T> CompletableFuture<T> submit(UUID playerId, Supplier<T> operation) {
    ExecutorService executor = executors.computeIfAbsent(playerId, id ->
        Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("qs-exchange-account-" + id + "-", 0).factory()));
    return CompletableFuture.supplyAsync(operation, executor);
  }

  @Override
  public void close() {
    executors.values().forEach(ExecutorService::shutdown);
  }
}
~~~

- [ ] **Step 4: 实现 deposit 的精确状态顺序**

~~~java
public CompletableFuture<TransferRecord> deposit(UUID requestId, UUID accountId,
                                                  String currencyId, BigDecimal amount) {
  return serialiser.submit(accountId, () -> {
    TransferRecord existing = transfers.findByRequest(accountId, requestId).orElse(null);
    if (existing != null) return existing;
    TransferRecord prepared = transfers.create(TransferRecord.prepared(
        ids.get(), requestId, accountId, TransferType.MONEY_DEPOSIT,
        currencyId, amount, clock.instant()));
    TransferRecord processing = transfers.transition(prepared.transferId(), prepared.version(),
        TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
    ExternalResult external;
    try {
      external = economy.withdraw(accountId, currencyId, amount);
    } catch (RuntimeException failure) {
      external = ExternalResult.UNKNOWN;
    }
    if (external == ExternalResult.FAILURE) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.FAILED, "economy withdrawal rejected");
    }
    if (external == ExternalResult.UNKNOWN) {
      return transfers.transition(processing.transferId(), processing.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED,
          "economy withdrawal result unknown");
    }
    return repository.inTransaction(tx -> {
      tx.creditAvailableCurrency(accountId, currencyId, amount);
      tx.appendJournal(TransferJournals.moneyDeposit(processing, clock.instant()));
      return tx.completeTransfer(processing.transferId(), processing.version());
    });
  });
}
~~~

把上述方法放入 MoneyTransferService；构造器明确注入 TransferRepository、ExchangeRepository、EconomyGateway、PlayerOperationSerialiser、Clock 和 Supplier<UUID>。ExchangeTransaction 增加 completeTransfer，并使用 status=PROCESSING/version 条件与余额/账本在同一 SQL 事务更新。

~~~java
com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord createTransfer(
    com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord prepared)
    throws java.sql.SQLException;
com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord completeTransfer(
    java.util.UUID transferId, long expectedVersion) throws java.sql.SQLException;
~~~

- [ ] **Step 5: 运行资金存入测试**

Run: mvn -pl addon/exchange -Dtest=MoneyDepositTest test

Expected: PASS；SUCCESS 只外部扣款一次并内部入账；FAILURE 为 FAILED；异常或 UNKNOWN 为 REVIEW_REQUIRED 且不内部入账。

- [ ] **Step 6: 提交资金存入**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/persistence addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyDepositTest.java
git commit -m "feat(exchange): add safe money deposits"
~~~

### Task 3: 实现资金提现状态机

**Files:**
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyTransferService.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferJournals.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyWithdrawalTest.java

- [ ] **Step 1: 写预冻结、成功消费、失败释放与未知保持冻结红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyWithdrawalTest {
  @Test
  void releasesReservationOnExplicitFailure() {
    TransferFixture fixture = TransferFixture.funded(
        (player, currency, amount) -> ExternalResult.FAILURE);
    TransferRecord result = fixture.money().withdraw(UUID.randomUUID(), fixture.player(), "USD",
        new BigDecimal("40.00")).join();
    assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
    assertThat(fixture.availableCurrency()).isEqualByComparingTo("100.00");
    assertThat(fixture.frozenCurrency()).isZero();
  }

  @Test
  void keepsReservationWhenPayoutIsUnknown() {
    TransferFixture fixture = TransferFixture.funded(
        (player, currency, amount) -> ExternalResult.UNKNOWN);
    TransferRecord result = fixture.money().withdraw(UUID.randomUUID(), fixture.player(), "USD",
        new BigDecimal("40.00")).join();
    assertThat(result.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
    assertThat(fixture.availableCurrency()).isEqualByComparingTo("60.00");
    assertThat(fixture.frozenCurrency()).isEqualByComparingTo("40.00");
  }
}
~~~

- [ ] **Step 2: 运行并确认 withdraw 尚未实现**

Run: mvn -pl addon/exchange -Dtest=MoneyWithdrawalTest test

Expected: FAIL，编译器报告 MoneyTransferService.withdraw 不存在。

- [ ] **Step 3: 事务内创建提现预留**

首次 requestId 在 repository.inTransaction 内同时执行：

~~~java
tx.freezeCurrency(accountId, currencyId, amount);
TransferRecord prepared = tx.createTransfer(TransferRecord.prepared(
    ids.get(), requestId, accountId, TransferType.MONEY_WITHDRAWAL,
    currencyId, amount, clock.instant()));
tx.appendJournal(TransferJournals.freezeMoneyWithdrawal(prepared, clock.instant()));
return prepared;
~~~

重复 requestId 直接返回既有记录，绝不再次冻结。

- [ ] **Step 4: 实现外部付款后三分支**

在玩家串行器内 CAS PREPARED→PROCESSING，再调用 economy.deposit：

- SUCCESS：同一 SQL 事务 consumeFrozenCurrency、append moneyWithdrawal journal、PROCESSING→COMPLETED。
- FAILURE：同一 SQL 事务 releaseCurrency、append withdrawalRelease journal、PROCESSING→FAILED。
- UNKNOWN 或 RuntimeException：只把 PROCESSING→REVIEW_REQUIRED，冻结额保持原样等待人工确认。

TransferJournals 的每个工厂都返回逐 assetId 平衡的 LedgerJournal；冻结只是内部 available/frozen 子账户迁移，总额不改变。

- [ ] **Step 5: 运行资金存取与故障回归**

Run: mvn -pl addon/exchange -Dtest=MoneyDepositTest,MoneyWithdrawalTest test

Expected: PASS；失败提现完整解冻，不确定提现保持冻结且不会自动第二次付款。

- [ ] **Step 6: 提交资金提现**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/MoneyWithdrawalTest.java
git commit -m "feat(exchange): add safe money withdrawals"
~~~

### Task 4: 适配 QuickShop EconomyProvider

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/QuickShopEconomyGateway.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/QuickShopEconomyGatewayTest.java

- [ ] **Step 1: 写 false、异常和成功映射红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuickShopEconomyGatewayTest {
  @Test
  void mapsProviderBooleanAndExceptionConservatively() {
    EconomyProviderFixture provider = new EconomyProviderFixture();
    QuickShopEconomyGateway gateway = provider.gateway("world");
    UUID player = UUID.randomUUID();

    provider.withdrawResult(true);
    assertThat(gateway.withdraw(player, "USD", BigDecimal.ONE))
        .isEqualTo(ExternalResult.SUCCESS);
    provider.withdrawResult(false);
    assertThat(gateway.withdraw(player, "USD", BigDecimal.ONE))
        .isEqualTo(ExternalResult.FAILURE);
    provider.throwOnWithdraw();
    assertThat(gateway.withdraw(player, "USD", BigDecimal.ONE))
        .isEqualTo(ExternalResult.UNKNOWN);
  }
}
~~~

- [ ] **Step 2: 运行并确认平台适配器缺失**

Run: mvn -pl addon/exchange -Dtest=QuickShopEconomyGatewayTest test

Expected: FAIL，编译器报告 QuickShopEconomyGateway 不存在。

- [ ] **Step 3: 实现 EconomyProvider 适配**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.transfer.EconomyGateway;
import com.ghostchu.quickshop.addon.exchange.transfer.model.ExternalResult;
import com.ghostchu.quickshop.api.economy.EconomyProvider;
import com.ghostchu.quickshop.obj.QUserImpl;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class QuickShopEconomyGateway implements EconomyGateway {
  private final QuickShop quickShop;
  private final String worldName;

  public QuickShopEconomyGateway(QuickShop quickShop, String worldName) {
    this.quickShop = quickShop;
    this.worldName = worldName;
  }

  @Override
  public ExternalResult withdraw(UUID playerId, String currencyId, BigDecimal amount) {
    return invoke(playerId, currencyId, amount, true);
  }

  @Override
  public ExternalResult deposit(UUID playerId, String currencyId, BigDecimal amount) {
    return invoke(playerId, currencyId, amount, false);
  }

  private ExternalResult invoke(UUID playerId, String currencyId,
                                BigDecimal amount, boolean withdraw) {
    try {
      EconomyProvider provider = Objects.requireNonNull(
          quickShop.getEconomyManager().provider(), "economy provider unavailable");
      var user = QUserImpl.createSync(quickShop.getPlayerFinder(), playerId);
      String providerCurrency = "default".equalsIgnoreCase(currencyId) ? null : currencyId;
      boolean success = withdraw
          ? provider.withdraw(user, worldName, providerCurrency, amount)
          : provider.deposit(user, worldName, providerCurrency, amount);
      return success ? ExternalResult.SUCCESS : ExternalResult.FAILURE;
    } catch (RuntimeException failure) {
      return ExternalResult.UNKNOWN;
    }
  }
}
~~~

调用该适配器只能发生在 PlayerOperationSerialiser 的工作线程，不得从 Bukkit/Folia 游戏线程直接调用。

- [ ] **Step 4: 运行适配器和资金服务测试**

Run: mvn -pl addon/exchange -Dtest=QuickShopEconomyGatewayTest,Money*Test test

Expected: PASS；false 是明确失败，异常是不确定结果，BigDecimal 不转换为 double。

- [ ] **Step 5: 提交经济适配器**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/QuickShopEconomyGateway.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/QuickShopEconomyGatewayTest.java
git commit -m "feat(exchange): adapt quickshop economy provider"
~~~

### Task 5: 实现普通材料和特殊物品严格指纹

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FingerprintMode.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprint.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintServiceTest.java

- [ ] **Step 1: 写数量归一、内部标记排除和元数据严格区分红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemFingerprintServiceTest {
  @Test
  void strictFingerprintIgnoresAmountAndTransferMarkerOnly() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack one = new ItemStack(Material.DIAMOND, 1);
    ItemStack sixtyFour = one.clone();
    sixtyFour.setAmount(64);
    fixture.mark(sixtyFour, UUID.randomUUID());
    ItemStack named = one.clone();
    named.editMeta(meta -> meta.setDisplayName("Special"));

    assertThat(fixture.service().fingerprint(one, FingerprintMode.STRICT))
        .isEqualTo(fixture.service().fingerprint(sixtyFour, FingerprintMode.STRICT));
    assertThat(fixture.service().fingerprint(named, FingerprintMode.STRICT))
        .isNotEqualTo(fixture.service().fingerprint(one, FingerprintMode.STRICT));
  }

  @Test
  void vanillaMaterialMarketRejectsMetadata() {
    ItemFingerprintFixture fixture = ItemFingerprintFixture.create();
    ItemStack named = new ItemStack(Material.DIAMOND);
    named.editMeta(meta -> meta.setDisplayName("Special"));
    assertThat(fixture.service().acceptsVanillaMaterial(named, Material.DIAMOND)).isFalse();
  }
}
~~~

- [ ] **Step 2: 运行并确认指纹类型缺失**

Run: mvn -pl addon/exchange -Dtest=ItemFingerprintServiceTest test

Expected: FAIL，编译器报告 ItemFingerprintService 不存在。

- [ ] **Step 3: 定义指纹模式和值**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;
public enum FingerprintMode { VANILLA_MATERIAL, STRICT }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

public record ItemFingerprint(String algorithm, String value) {
  public ItemFingerprint {
    if (!algorithm.equals("material-v1") && !algorithm.equals("sha256-stack-v1")) {
      throw new IllegalArgumentException("unsupported fingerprint algorithm");
    }
  }
}
~~~

- [ ] **Step 4: 实现规范化与 SHA-256**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class ItemFingerprintService {
  private final QuickShop quickShop;
  private final NamespacedKey transferMarker;

  public ItemFingerprintService(QuickShop quickShop, NamespacedKey transferMarker) {
    this.quickShop = quickShop;
    this.transferMarker = transferMarker;
  }

  public ItemFingerprint fingerprint(ItemStack source, FingerprintMode mode) {
    if (source == null || source.getType().isAir()) throw new IllegalArgumentException("item is empty");
    ItemStack normalized = source.clone();
    normalized.setAmount(1);
    normalized.editMeta(meta -> meta.getPersistentDataContainer().remove(transferMarker));
    if (mode == FingerprintMode.VANILLA_MATERIAL) {
      if (!acceptsVanillaMaterial(normalized, normalized.getType())) {
        throw new IllegalArgumentException("material market accepts unmodified items only");
      }
      return new ItemFingerprint("material-v1", normalized.getType().getKey().asString());
    }
    String encoded = quickShop.platform().encodeStack(normalized);
    return new ItemFingerprint("sha256-stack-v1", sha256(encoded));
  }

  public boolean acceptsVanillaMaterial(ItemStack candidate, Material material) {
    ItemStack normalized = candidate.clone();
    normalized.setAmount(1);
    normalized.editMeta(meta -> meta.getPersistentDataContainer().remove(transferMarker));
    ItemStack vanilla = new ItemStack(material, 1);
    return normalized.getType() == material
        && quickShop.getItemMatcher().matches(vanilla, normalized)
        && quickShop.getItemMatcher().matches(normalized, vanilla)
        && quickShop.platform().encodeStack(normalized)
            .equals(quickShop.platform().encodeStack(vanilla));
  }

  private static String sha256(String encoded) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(encoded.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
~~~

标准模板入库前强制 amount=1 且去除 exchange transfer marker。禁止把装有物品的容器、地图 ID、随机组件或损耗状态放入 VANILLA_MATERIAL 市场；这些只能由管理员显式创建 STRICT 市场，且仍需确认完全同质。

- [ ] **Step 5: 运行指纹测试**

Run: mvn -pl addon/exchange -Dtest=ItemFingerprintServiceTest test

Expected: PASS；amount 和内部 marker 不改变严格指纹，任何其他元数据改变都会改变指纹。

- [ ] **Step 6: 提交物品指纹**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintServiceTest.java
git commit -m "feat(exchange): enforce fungible item fingerprints"
~~~

### Task 6: 在 Folia 安全上下文实现背包网关

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/InventoryGateway.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/InventoryResult.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGateway.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGatewayTest.java

- [ ] **Step 1: 写离线、空间不足、标记移除和线程调度红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.InventoryResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FoliaInventoryGatewayTest {
  @Test
  void neverMutatesInventoryOutsidePlayerScheduler() {
    InventoryGatewayFixture fixture = InventoryGatewayFixture.onlineWithFreeSlots(0);
    UUID transfer = UUID.randomUUID();

    InventoryResult result = fixture.gateway()
        .deliverMarked(fixture.playerId(), fixture.template(), 64, transfer).join();

    assertThat(result).isEqualTo(InventoryResult.NO_SPACE);
    assertThat(fixture.schedulerCalls()).isEqualTo(1);
    assertThat(fixture.inventoryMutatedOutsideScheduler()).isFalse();
    assertThat(fixture.markedQuantity(transfer)).isZero();
  }
}
~~~

- [ ] **Step 2: 运行并确认背包网关缺失**

Run: mvn -pl addon/exchange -Dtest=FoliaInventoryGatewayTest test

Expected: FAIL，编译器报告 FoliaInventoryGateway 或 InventoryResult 不存在。

- [ ] **Step 3: 定义可恢复背包操作端口**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;
public enum InventoryResult { SUCCESS, OFFLINE, NO_SPACE, NOT_ENOUGH_MATCHING_ITEMS, UNKNOWN }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface InventoryGateway {
  CompletableFuture<InventoryResult> markForDeposit(
      UUID playerId, ItemStack template, long quantity, UUID transferId);
  CompletableFuture<InventoryResult> removeMarked(UUID playerId, UUID transferId, long quantity);
  CompletableFuture<InventoryResult> deliverMarked(
      UUID playerId, ItemStack template, long quantity, UUID transferId);
  CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId);
  CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId);
}
~~~

- [ ] **Step 4: 实现实体调度和全有或全无变更**

FoliaInventoryGateway 的统一调度包装：

~~~java
private <T> java.util.concurrent.CompletableFuture<T> atPlayer(
    java.util.UUID playerId, java.util.function.Function<org.bukkit.entity.Player,T> action,
    T offlineResult) {
  var future = new java.util.concurrent.CompletableFuture<T>();
  org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(playerId);
  if (player == null || !player.isOnline()) {
    future.complete(offlineResult);
    return future;
  }
  com.ghostchu.quickshop.QuickShop.folia().getScheduler().runAtEntityLater(player, () -> {
    try {
      if (!player.isOnline()) future.complete(offlineResult);
      else future.complete(action.apply(player));
    } catch (RuntimeException failure) {
      future.completeExceptionally(failure);
    }
  }, 1);
  return future;
}
~~~

markForDeposit 在一个回调中先扫描所有严格匹配 stack，数量不足则不修改；足够时按 slot 顺序拆分需要的数量，并给选中 stack 写 PersistentDataType.STRING transferId。removeMarked 只移除相同 transferId 的 stack。deliverMarked 在同一个实体线程回调中复制 ItemStack[] contents，并用纯 Java 容量模拟器按相同物品可合并空间和空槽计算全部 stack；容量不足则返回 NO_SPACE 且不动真实背包，可容纳时才把带 marker、按 maxStackSize 拆分的 stacks 加入真实背包。容量模拟器和真实 addItem 处于同一个回调，中间没有异步间隙；若仍返回 leftovers，立即移除本 transferId 已插入的 stack 并返回 UNKNOWN。clearMarker 只清除给定 transferId，不改其他 PDC。

- [ ] **Step 5: 运行背包网关测试**

Run: mvn -pl addon/exchange -Dtest=FoliaInventoryGatewayTest test

Expected: PASS；所有 PlayerInventory 访问发生在实体调度回调，空间不足不部分发放也不向地面丢物品。

- [ ] **Step 6: 提交 Folia 背包边界**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/InventoryGateway.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/InventoryResult.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGateway.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/FoliaInventoryGatewayTest.java
git commit -m "feat(exchange): add folia-safe inventory gateway"
~~~

### Task 7: 实现物品存入、提现和待领取

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/ItemTransferService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/ItemTransferServiceTest.java

- [ ] **Step 1: 写成功存入、空间不足待领取和重复点击红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemTransferServiceTest {
  @Test
  void depositsMarkedItemsOnce() {
    TransferFixture fixture = TransferFixture.withInventoryItems(64);
    UUID request = UUID.randomUUID();
    TransferRecord first = fixture.items().deposit(request, fixture.player(),
        "diamond-usd", 32).join();
    TransferRecord duplicate = fixture.items().deposit(request, fixture.player(),
        "diamond-usd", 32).join();
    assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(duplicate.transferId()).isEqualTo(first.transferId());
    assertThat(fixture.externalItemQuantity()).isEqualTo(32);
    assertThat(fixture.internalAvailableItems()).isEqualTo(32);
  }

  @Test
  void withdrawalStaysPreparedWhenInventoryIsFull() {
    TransferFixture fixture = TransferFixture.withInternalItemsAndFullInventory(64);
    TransferRecord result = fixture.items().withdraw(UUID.randomUUID(), fixture.player(),
        "diamond-usd", 32).join();
    assertThat(result.status()).isEqualTo(TransferStatus.PREPARED);
    assertThat(fixture.internalFrozenItems()).isEqualTo(32);
    assertThat(fixture.droppedItems()).isZero();
  }
}
~~~

- [ ] **Step 2: 运行并确认 ItemTransferService 缺失**

Run: mvn -pl addon/exchange -Dtest=ItemTransferServiceTest test

Expected: FAIL，编译器报告 ItemTransferService 不存在。

- [ ] **Step 3: 实现物品存入**

固定顺序：

1. 创建 PREPARED；重复 requestId 返回既有记录。
2. InventoryGateway.markForDeposit 严格匹配并标记完整数量。
3. 标记失败：OFFLINE 保持 PREPARED，NOT_ENOUGH_MATCHING_ITEMS 转 FAILED，UNKNOWN 转 REVIEW_REQUIRED。
4. CAS PREPARED→PROCESSING。
5. removeMarked 成功后，在同一 SQL 事务 creditAvailableItems、append itemDeposit journal、PROCESSING→COMPLETED。
6. removeMarked 明确未找到足量标记时进入 REVIEW_REQUIRED；不能假设物品仍在玩家处。
7. 完成后 clearMarker 作为清理动作；理论上存入已移除，不影响资产结算。

- [ ] **Step 4: 实现物品提现和待领取**

固定顺序：

1. SQL 事务 freezeItems + create PREPARED + freeze journal；重复 requestId 不重复冻结。
2. InventoryGateway.deliverMarked 返回 NO_SPACE/OFFLINE 时保持 PREPARED，供玩家以后点击领取。
3. 准备发放前 CAS PREPARED→PROCESSING；为了避免先推进再因空间变化不确定，deliverMarked 内部再次检查空间，若 NO_SPACE 则 CAS PROCESSING→PREPARED 需要新增显式合法边，且该边只允许 external marker 数量为 0 时使用。
4. SUCCESS 后 SQL 事务 consumeFrozenItems + append withdrawal journal + PROCESSING→COMPLETED。
5. COMPLETED 后 clearMarker；清理失败不改变完成状态，登录恢复继续清理。
6. UNKNOWN 进入 REVIEW_REQUIRED 并保持 frozenQuantity。

为第 3 点扩展 TransferRepository.transitionGuarded：PROCESSING→PREPARED 只有 recoveryEvidence 等于 NO_MARKED_ITEMS 时允许，记录 audit reason 为 inventory-capacity-race。

~~~java
TransferRecord transitionGuarded(UUID transferId, long expectedVersion,
                                 TransferStatus expectedStatus, TransferStatus targetStatus,
                                 RecoveryEvidence evidence, String reason)
    throws java.sql.SQLException;
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;
public enum RecoveryEvidence { NO_MARKED_ITEMS }
~~~

- [ ] **Step 5: 运行物品转账和全量转账测试**

Run: mvn -pl addon/exchange -Dtest=ItemTransferServiceTest,Money*Test,JdbcTransferRepositoryTest test

Expected: PASS；存入/提现均幂等；背包已满保留待领取冻结，不丢地、不重复发放。

- [ ] **Step 6: 提交物品转账**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/ItemTransferServiceTest.java
git commit -m "feat(exchange): add recoverable item custody"
~~~

### Task 8: 启动与登录恢复，不确定窗口进入人工审核

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryService.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/TransferLoginListener.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryServiceTest.java

- [ ] **Step 1: 写所有中断状态的参数化红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.transfer.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransferRecoveryServiceTest {
  @ParameterizedTest
  @CsvSource({
      "MONEY_DEPOSIT,PROCESSING,REVIEW_REQUIRED",
      "MONEY_WITHDRAWAL,PROCESSING,REVIEW_REQUIRED",
      "ITEM_DEPOSIT,PROCESSING,REVIEW_REQUIRED",
      "ITEM_WITHDRAWAL,PROCESSING,COMPLETED"
  })
  void recoversOnlyWhenExternalMarkerProvesOutcome(
      TransferType type, TransferStatus before, TransferStatus expected) {
    TransferFixture fixture = TransferFixture.interrupted(type, before);
    if (type == TransferType.ITEM_WITHDRAWAL) fixture.putMarkedDeliveryInInventory();

    TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

    assertThat(recovered.status()).isEqualTo(expected);
  }
}
~~~

- [ ] **Step 2: 运行并确认恢复服务缺失**

Run: mvn -pl addon/exchange -Dtest=TransferRecoveryServiceTest test

Expected: FAIL，编译器报告 TransferRecoveryService 不存在。

- [ ] **Step 3: 实现保守恢复矩阵**

TransferRecoveryService 对每条未完成记录使用以下确定规则：

| 类型 | 状态/证据 | 恢复动作 |
|---|---|---|
| 资金存入 | PREPARED | 可安全重新开始外部扣款 |
| 资金存入 | PROCESSING | REVIEW_REQUIRED，禁止自动再扣 |
| 资金提现 | PREPARED | 可安全开始付款 |
| 资金提现 | PROCESSING | REVIEW_REQUIRED，禁止自动再付 |
| 物品存入 | PREPARED 且 marker 存在 | 清 marker 后重新开始 |
| 物品存入 | PROCESSING 且 marker 仍足量 | 明确尚未移除，清 marker 并 FAILED |
| 物品存入 | PROCESSING 且 marker 不足/玩家离线 | REVIEW_REQUIRED |
| 物品提现 | PREPARED | 保留待领取 |
| 物品提现 | PROCESSING 且 marker 足量 | 完成 SQL 消费并清 marker |
| 物品提现 | PROCESSING 且 marker 不足/玩家离线 | REVIEW_REQUIRED |
| 任意 | COMPLETED 且 marker 存在 | 只清理 marker，不再变更资产 |

- [ ] **Step 4: 登录监听只提交恢复任务**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class TransferLoginListener implements Listener {
  private final TransferRecoveryService recovery;

  public TransferLoginListener(TransferRecoveryService recovery) {
    this.recovery = recovery;
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    recovery.recoverPlayer(event.getPlayer().getUniqueId());
  }
}
~~~

recoverPlayer 立即返回 CompletableFuture，不在登录事件线程运行 SQL；需要背包证据时通过 InventoryGateway 切回玩家实体上下文。插件启动调用 recoverAllMoneyTransfers，把遗留 PROCESSING 资金转账直接标记 REVIEW_REQUIRED；物品转账等待对应玩家在线再检查 marker。

TransferRecoveryService 的公开签名固定为：

~~~java
public java.util.concurrent.CompletableFuture<java.util.List<TransferRecord>>
recoverPlayer(java.util.UUID accountId)

public java.util.List<TransferRecord> recoverAllMoneyTransfers()
    throws java.sql.SQLException
~~~

- [ ] **Step 5: 运行崩溃窗口和完整验证**

Run: mvn -pl addon/exchange -Dtest=TransferRecoveryServiceTest,ItemTransferServiceTest,Money*Test test

Expected: PASS；没有任何 PROCESSING 资金转账被自动重放；有确定 marker 证据的物品提现只结算一次。

Run: mvn -pl addon/exchange verify

Expected: BUILD SUCCESS，Phase 1–3 测试全部通过。

- [ ] **Step 6: 提交转账恢复**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryService.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/TransferLoginListener.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/transfer/TransferRecoveryServiceTest.java
git commit -m "feat(exchange): recover interrupted custody transfers"
~~~

## Phase 3 验收

Run: rg -n "double|float" addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/transfer addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform

Expected: 不出现资产金额使用 double/float 的字段或转换。

Run: mvn -pl addon/exchange -Dtest='*Transfer*Test,*GatewayTest,*Fingerprint*Test' test

Expected: BUILD SUCCESS；外部经济或背包调用的异常均不会自动重试；资金不确定结果和无证据的物品中断进入 REVIEW_REQUIRED；背包不足保留待领取；所有 PlayerInventory 变更经过 Folia 实体调度。
