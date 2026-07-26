# Exchange Phase 4 Addon UI and Operations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 把撮合、持久化和托管服务装配成默认关闭、可在 Paper/Folia 上运行的 QuickShop Addon，并交付玩家 GUI、管理命令、行情、审计、指标和渐进上线工具。

**Architecture:** Main 是 composition root，只持有 ExchangeRuntime；命令和 TNML 页面通过只读 view/service 接口访问后台，不直接碰 SQL 或订单簿。配置区分结构参数与可热重载风险参数；启动先迁移、取得单写锁并恢复，关闭先拒绝新写入再排空队列。

**Tech Stack:** Java 21、Bukkit/Paper/Folia、QuickShop CommandContainer/Text/EasySQL、TNML Menu、Adventure、JDBC、JUnit Jupiter

---

## 前置条件与文件结构

先完成并验证 Phase 1、Phase 2 和 Phase 3 计划。

- Modify: addon/exchange/src/main/resources/plugin.yml — 命令、别名与完整权限边界。
- Create: addon/exchange/src/main/resources/config.yml — 功能开关、风险默认值、刷新率、世界/货币。
- Create: addon/exchange/src/main/resources/markets.yml — 示例市场与结构参数。
- Create: addon/exchange/src/main/resources/messages.yml — 中英可读消息键；通过 AddonMessageService 读取并发送 Adventure Component。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/*.java — MarketDefinition、MarketRegistry、结构变更守卫。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/*.java — 单写锁、装配、启动恢复和有序关闭。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/*.java — /quickshop exchange、/qse 和管理员子命令。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/*.java — TNML 菜单、页面、Presenter 和 1Hz 刷新。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/*.java — ticker、1m OHLCV 和 24h 汇总。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/*.java — 审计告警、强撤、核对、导出与指标。
- Create: docs/exchange-operations.md — 部署、白名单、备份、恢复和扩容手册。

### Task 1: 读取市场配置并保护结构性热重载

**Files:**
- Create: addon/exchange/src/main/resources/config.yml
- Create: addon/exchange/src/main/resources/markets.yml
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketDefinition.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistry.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config/MarketStateReader.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/config/MarketRegistryTest.java

- [ ] **Step 1: 写默认值、结构变更拒绝和风险热重载红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRegistryTest {
  @Test
  void loadsConfirmedRiskDefaults() {
    MarketRegistry registry = MarketRegistryFixture.loadDefault();
    MarketDefinition diamond = registry.require("minecraft_diamond/default");
    assertThat(diamond.risk().priceCageRatio()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().defaultMarketSlippage()).isEqualByComparingTo("0.05");
    assertThat(diamond.risk().maximumMarketSlippage()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().operationsPerSecond()).isEqualTo(5);
    assertThat(diamond.risk().operationsPerMinute()).isEqualTo(60);
  }

  @Test
  void structuralReloadRequiresPausedEmptyBook() {
    MarketRegistry registry = MarketRegistryFixture.loadDefault();
    MarketStateReader state = market -> new MarketStateReader.State(MarketStatus.OPEN, 3);
    assertThatThrownBy(() -> registry.reload(
        MarketRegistryFixture.changedTickSize(), state))
        .hasMessageContaining("structural change requires PAUSED market with no open orders");
  }
}
~~~

- [ ] **Step 2: 运行并确认配置类型缺失**

Run: mvn -pl addon/exchange -Dtest=MarketRegistryTest test

Expected: FAIL，编译器报告 MarketRegistry 等类型不存在。

- [ ] **Step 3: 写默认总配置**

addon/exchange/src/main/resources/config.yml：

~~~yaml
config-version: 1
enabled: false
database:
  mode: quickshop
economy:
  world: world
  default-currency: default
market-data:
  gui-refresh-ticks: 20
  candle-retention-days: 365
risk-defaults:
  price-cage-ratio: '0.20'
  default-market-slippage: '0.05'
  maximum-market-slippage: '0.20'
  level-one-move: '0.10'
  level-one-halt-seconds: 120
  level-two-move: '0.20'
  level-two-halt-seconds: 600
  operations-per-second: 5
  operations-per-minute: 60
operations:
  reconciliation-interval-minutes: 1440
  audit-export-directory: audit
rollout:
  whitelist-enabled: true
  allowed-players: []
~~~

addon/exchange/src/main/resources/markets.yml：

~~~yaml
config-version: 1
markets:
  minecraft_diamond/default:
    enabled: false
    display-name: Diamond / Default Currency
    item:
      mode: VANILLA_MATERIAL
      material: DIAMOND
    currency: default
    base-price: '100.00'
    min-price: '1.00'
    max-price: '10000.00'
    tick-size: '0.01'
    price-scale: 2
    currency-scale: 2
    min-quantity: 1
    max-quantity: 2304
    discovery-quantity: 100
    maker-fee-rate: '0.001'
    taker-fee-rate: '0.002'
    max-account-holding: 100000
    max-frozen-currency: '10000000.00'
    max-open-orders: 100
    block-container-shops: false
~~~

- [ ] **Step 4: 定义完整市场配置和值相等分区**

~~~java
package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;

public record MarketDefinition(
    String marketId, String displayName, boolean enabled,
    ItemDefinition item, StructuralRules structural, RiskRules risk,
    boolean blockContainerShops) {

  public record ItemDefinition(FingerprintMode mode, String material,
                               String encodedTemplate, String fingerprint) {}

  public record StructuralRules(
      String currencyId, BigDecimal basePrice, BigDecimal minPrice, BigDecimal maxPrice,
      BigDecimal tickSize, int priceScale, int currencyScale,
      long minQuantity, long maxQuantity, long discoveryQuantity) {}

  public record RiskRules(
      BigDecimal makerFeeRate, BigDecimal takerFeeRate,
      BigDecimal priceCageRatio, BigDecimal defaultMarketSlippage,
      BigDecimal maximumMarketSlippage, BigDecimal levelOneMove,
      long levelOneHaltSeconds, BigDecimal levelTwoMove, long levelTwoHaltSeconds,
      long maxAccountHolding, BigDecimal maxFrozenCurrency, int maxOpenOrders,
      int operationsPerSecond, int operationsPerMinute) {}
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;

@FunctionalInterface
public interface MarketStateReader {
  State read(String marketId);
  record State(MarketStatus status, int openOrders) {}
}
~~~

MarketRegistry.load 逐字段使用 new BigDecimal(String)，验证：

- discoveryQuantity 至少是 10 × minQuantity，示例为 100。
- 费率不为负；首版不允许返佣。
- tickSize 与 minPrice/maxPrice 符合 priceScale。
- 默认滑点 ≤ 最大滑点 ≤ 0.20。
- 最大持仓、最大冻结资金、最大开放订单数均为正。
- 特殊 STRICT 市场必须同时有 encodedTemplate 和 fingerprint。

reload 对每个 marketId 比较 ItemDefinition 与 StructuralRules；不同则只在 state.status=PAUSED 且 openOrders=0 时替换，并递增 structuralVersion。RiskRules 改变时递增 riskVersion；makerFeeRate 或 takerFeeRate 改变时另外递增 feeVersion，并把旧版和新版费率都保存在 exchange_markets.fee_schedule_payload 的版本映射中。新 Order 的 configVersion 保存 structuralVersion，并同时保存 feeVersion；开放订单结算始终按 order.feeVersion 从该不可变版本映射读取费率，重启后也不会套用新费率。只有确认数据库不存在引用某 feeVersion 的开放订单后，归档维护才可清理该旧版本；其他热重载限额只验证新请求。

- [ ] **Step 5: 运行配置测试**

Run: mvn -pl addon/exchange -Dtest=MarketRegistryTest test

Expected: PASS；确认的 20%/5%/20%、5/s、60/min 默认值精确加载，开放订单市场不能改 tick/货币/指纹。

- [ ] **Step 6: 提交市场配置**

~~~bash
git add addon/exchange/src/main/resources/config.yml addon/exchange/src/main/resources/markets.yml addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/config addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/config
git commit -m "feat(exchange): load guarded market configuration"
~~~

### Task 2: 补齐频率、持仓和开放订单风险检查

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/OrderRateLimiter.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/AccountRiskSnapshot.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/OrderRiskService.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk/OrderRiskServiceTest.java

- [ ] **Step 1: 写 5/s、60/min、持仓、冻结额和订单数红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRiskServiceTest {
  @Test
  void rejectsSixthOperationInSecondAndSixtyFirstInMinute() {
    OrderRateLimiter limiter = new OrderRateLimiter(5, 60);
    UUID account = UUID.randomUUID();
    Instant now = Instant.EPOCH;
    for (int i = 0; i < 5; i++) assertThat(limiter.allow(account, now)).isTrue();
    assertThat(limiter.allow(account, now)).isFalse();
    assertThat(limiter.allow(account, now.plusSeconds(1))).isTrue();
  }

  @Test
  void enforcesAccountExposureLimits() {
    AccountRiskSnapshot snapshot = new AccountRiskSnapshot(
        100_000, new BigDecimal("10000000.00"), 100);
    assertThat(snapshot.canAddHolding(1, 100_000)).isFalse();
    assertThat(snapshot.canFreeze(new BigDecimal("0.01"), new BigDecimal("10000000.00"))).isFalse();
    assertThat(snapshot.canOpenOrder(100)).isFalse();
  }
}
~~~

- [ ] **Step 2: 运行并确认账户风险类型缺失**

Run: mvn -pl addon/exchange -Dtest=OrderRiskServiceTest test

Expected: FAIL，编译器报告 OrderRateLimiter 或 AccountRiskSnapshot 不存在。

- [ ] **Step 3: 实现双滑动窗口限流器**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class OrderRateLimiter {
  private final int perSecond;
  private final int perMinute;
  private final Map<UUID, ArrayDeque<Instant>> events = new ConcurrentHashMap<>();

  public OrderRateLimiter(int perSecond, int perMinute) {
    this.perSecond = perSecond;
    this.perMinute = perMinute;
  }

  public boolean allow(UUID accountId, Instant now) {
    ArrayDeque<Instant> queue = events.computeIfAbsent(accountId, ignored -> new ArrayDeque<>());
    synchronized (queue) {
      Instant minuteCutoff = now.minusSeconds(60);
      while (!queue.isEmpty() && !queue.peekFirst().isAfter(minuteCutoff)) queue.removeFirst();
      long inSecond = queue.stream().filter(event -> event.isAfter(now.minusSeconds(1))).count();
      if (inSecond >= perSecond || queue.size() >= perMinute) return false;
      queue.addLast(now);
      return true;
    }
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;

public record AccountRiskSnapshot(long holding, BigDecimal frozenCurrency, int openOrders) {
  public boolean canAddHolding(long added, long maximum) {
    return added >= 0 && holding <= maximum - added;
  }
  public boolean canFreeze(BigDecimal added, BigDecimal maximum) {
    return added.signum() >= 0 && frozenCurrency.add(added).compareTo(maximum) <= 0;
  }
  public boolean canOpenOrder(int maximum) {
    return openOrders < maximum;
  }
}
~~~

OrderRiskService.check 组合 MarketStatus、MarketRules、RiskLimits、OrderRateLimiter 和 AccountRiskSnapshot，返回明确 RejectReason：MARKET_NOT_OPEN、RATE_LIMITED、PRICE_OUTSIDE_CAGE、SLIPPAGE_TOO_HIGH、HOLDING_LIMIT、FROZEN_LIMIT、OPEN_ORDER_LIMIT、SELF_TRADE。OrderService 在进入市场队列前检查一次，SQL 事务内读取最新版本后再检查一次。

- [ ] **Step 4: 运行账户风险测试**

Run: mvn -pl addon/exchange -Dtest=OrderRiskServiceTest,MarketRiskTest test

Expected: PASS；第 6 次/秒和第 61 次/分钟被拒，边界相等时持仓/冻结/订单上限不再接受新增。

- [ ] **Step 5: 提交账户风险**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk
git commit -m "feat(exchange): enforce account order limits"
~~~

### Task 3: 装配启动、单写锁、恢复与有序关闭

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/SingleWriterGuard.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeTest.java

- [ ] **Step 1: 写默认关闭、恢复后接单和关闭排队红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRuntimeTest {
  @Test
  void rejectsWritesUntilRecoveryAndDrainsBeforeClose() throws Exception {
    RuntimeFixture fixture = RuntimeFixture.create();
    ExchangeRuntime runtime = fixture.runtime();
    assertThat(runtime.acceptingWrites()).isFalse();
    runtime.start();
    assertThat(runtime.acceptingWrites()).isTrue();
    fixture.enqueueBlockedCommand();
    var closing = fixture.closeAsync(runtime);
    assertThat(runtime.acceptingWrites()).isFalse();
    fixture.releaseCommand();
    closing.get();
    assertThat(fixture.pendingCommands()).isZero();
    assertThat(fixture.writerLockReleased()).isTrue();
  }
}
~~~

- [ ] **Step 2: 运行并确认 runtime 类型缺失**

Run: mvn -pl addon/exchange -Dtest=ExchangeRuntimeTest test

Expected: FAIL，编译器报告 ExchangeRuntime 不存在。

- [ ] **Step 3: 实现数据库单写守卫**

SingleWriterGuard.acquire：

- SQLite：确认 JDBC URL 为本地 sqlite，并依赖单插件文件锁；不允许 config 指向共享网络 SQLite。
- MySQL：打开专用长连接并执行 SELECT GET_LOCK(lockName,0)，lockName 为 dbPrefix + exchange_writer；返回 0/null 时启动失败。
- close：MySQL 执行 SELECT RELEASE_LOCK(lockName) 后关闭专用连接。
- 专用锁连接断开时立即把所有市场设为 RECOVERING 并停止接收写入；首版不自动抢回锁。

~~~java
package com.ghostchu.quickshop.addon.exchange.runtime;

public interface SingleWriterGuard extends AutoCloseable {
  void acquire() throws Exception;
  boolean held();
  @Override void close() throws Exception;
}
~~~

- [ ] **Step 4: 实现 ExchangeRuntime 生命周期**

~~~java
package com.ghostchu.quickshop.addon.exchange.runtime;

import com.ghostchu.quickshop.addon.exchange.core.service.MarketDispatcher;
import com.ghostchu.quickshop.addon.exchange.service.OrderBookRecoveryService;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ExchangeRuntime implements AutoCloseable {
  private final SingleWriterGuard writer;
  private final OrderBookRecoveryService books;
  private final TransferRecoveryService transfers;
  private final MarketDispatcher dispatcher;
  private final AtomicBoolean acceptingWrites = new AtomicBoolean();

  public ExchangeRuntime(SingleWriterGuard writer, OrderBookRecoveryService books,
                         TransferRecoveryService transfers, MarketDispatcher dispatcher) {
    this.writer = writer;
    this.books = books;
    this.transfers = transfers;
    this.dispatcher = dispatcher;
  }

  public void start() throws Exception {
    writer.acquire();
    books.recoverAll();
    transfers.recoverAllMoneyTransfers();
    acceptingWrites.set(true);
  }

  public boolean acceptingWrites() {
    return acceptingWrites.get() && writer.held();
  }

  @Override
  public void close() throws Exception {
    acceptingWrites.set(false);
    dispatcher.close();
    writer.close();
  }
}
~~~

ExchangeRuntimeFactory 使用 QuickShop.getInstance().getSqlManager()::getConnection、getDbPrefix()、getEconomyManager().provider()、platform() 和 Folia 调度器装配 Phase 1–3 的全部具体实现。创建顺序固定为：读配置→检测 dialect→迁移→仓储→市场注册→恢复服务→转账服务→撮合/命令服务→行情/审计。

启动后用后台 ScheduledExecutorService 检查 HALTED 市场；只有 haltedUntil 已到、writer lock 仍持有且数据库版本未变时，才以熔断前持久化的 referencePrice 把状态恢复为 OPEN。PAUSED/CLOSED 不自动恢复。系统不实现每日闭市；除熔断和管理员状态外全天连续接单。

- [ ] **Step 5: 修改 Main，只在 enabled=true 启动运行时**

~~~java
package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.runtime.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  private ExchangeRuntime runtime;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    if (!getConfig().getBoolean("enabled", false)) {
      getLogger().info("QuickShop Exchange is disabled in config.yml");
      return;
    }
    try {
      runtime = new ExchangeRuntimeFactory(this, QuickShop.getInstance()).create();
      runtime.start();
    } catch (Exception failure) {
      getLogger().log(java.util.logging.Level.SEVERE, "Exchange startup failed safely", failure);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (runtime == null) return;
    try {
      runtime.close();
    } catch (Exception failure) {
      getLogger().log(java.util.logging.Level.SEVERE, "Exchange shutdown failed", failure);
    }
  }
}
~~~

- [ ] **Step 6: 运行生命周期和恢复回归**

Run: mvn -pl addon/exchange -Dtest=ExchangeRuntimeTest,OrderBookRecoveryServiceTest,TransferRecoveryServiceTest test

Expected: PASS；未恢复时不接写，关闭先拒绝新命令再排空，每个 MySQL 数据库只能持有一个 writer lock。

- [ ] **Step 7: 提交运行时装配**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime
git commit -m "feat(exchange): assemble recoverable single-writer runtime"
~~~

### Task 4: 注册玩家命令、/qse 别名和权限

**Files:**
- Modify: addon/exchange/src/main/resources/plugin.yml
- Create: addon/exchange/src/main/resources/messages.yml
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouter.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/CommandActor.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/BukkitCommandActor.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/SubCommandExchange.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/QseAliasCommand.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/AddonMessageService.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouterTest.java

- [ ] **Step 1: 写权限、参数和 requestId 生成红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCommandRouterTest {
  @Test
  void deniesMarketOrderWithoutDedicatedPermission() {
    CommandFixture fixture = CommandFixture.playerWith("quickshop.exchange.use");
    fixture.router().execute(fixture.sender(), new String[]{"order","market","buy","diamond-usd","5"});
    assertThat(fixture.lastMessageKey()).isEqualTo("permission-denied");
    assertThat(fixture.orderRequests()).isEmpty();
  }

  @Test
  void generatesOneRequestIdPerConfirmedAction() {
    CommandFixture fixture = CommandFixture.playerWith(
        "quickshop.exchange.use", "quickshop.exchange.order.limit");
    fixture.router().execute(fixture.sender(),
        new String[]{"order","limit","buy","diamond-usd","100.00","5"});
    assertThat(fixture.orderRequests()).singleElement()
        .satisfies(request -> assertThat(request.requestId()).isNotNull());
  }
}
~~~

- [ ] **Step 2: 运行并确认命令类型缺失**

Run: mvn -pl addon/exchange -Dtest=ExchangeCommandRouterTest test

Expected: FAIL，编译器报告 ExchangeCommandRouter 不存在。

- [ ] **Step 3: 声明命令和权限**

plugin.yml 扩展为：

~~~yaml
name: qssuite-exchange
version: '${project.version}'
main: com.ghostchu.quickshop.addon.${project.artifactId}.Main
folia-supported: true
api-version: '1.20'
depend: [QuickShop-Hikari]
authors: [Ghost_chu]
commands:
  qse:
    description: Open QuickShop Exchange
    usage: /qse
permissions:
  quickshop.exchange.use: {default: true}
  quickshop.exchange.deposit: {default: true}
  quickshop.exchange.withdraw: {default: true}
  quickshop.exchange.order.limit: {default: true}
  quickshop.exchange.order.market: {default: true}
  quickshop.exchange.admin.market: {default: op}
  quickshop.exchange.admin.orders: {default: op}
  quickshop.exchange.admin.recovery: {default: op}
  quickshop.exchange.admin.audit: {default: op}
~~~

普通管理权限不包含 recovery/audit 子权限；代码逐项 hasPermission 检查，不使用一个 admin 通配判断替代。

messages.yml 使用以下初始键；AddonMessageService 通过 MiniMessage 解析并按玩家 locale 选择 zh-CN/en-US，缺失 locale 回退 en-US：

~~~yaml
en-US:
  command-description: Open the central item exchange
  permission-denied: You do not have permission for this exchange action.
  market-not-open: This market currently accepts queries and cancellations only.
  request-accepted: 'Exchange request accepted: <requestId>'
  review-required: This transfer needs administrator review and will not be retried automatically.
  inventory-full: Your inventory is full; the withdrawal remains ready to claim.
zh-CN:
  command-description: 打开中央物品交易所
  permission-denied: 你没有执行此交易所操作的权限。
  market-not-open: 当前市场只允许查询和撤单。
  request-accepted: '交易请求已受理：<requestId>'
  review-required: 此转账需要管理员审核，系统不会自动重试。
  inventory-full: 背包空间不足，提现将保留为待领取。
~~~

- [ ] **Step 4: 实现共享路由和两个入口**

ExchangeCommandRouter 接受 CommandActor、args 并路由：

~~~java
package com.ghostchu.quickshop.addon.exchange.command;

import java.util.UUID;

public interface CommandActor {
  UUID accountId();
  boolean hasPermission(String permission);
  void message(String key, Object... arguments);
  void openMenu(String menuName, int page);
}
~~~

BukkitCommandActor 保存 Player、AddonMessageService 和菜单打开函数；accountId 返回 player.getUniqueId，权限委托 player.hasPermission，message 通过 Adventure 发送，openMenu 通过 QuickShop.createMenuPlayer 与 TNML 打开。测试使用同接口的内存实现，不依赖 Bukkit。

- 无参数或 open：打开市场列表。
- market marketId：打开详情。
- order limit side market price quantity：要求 limit 权限并打开确认页。
- order market side market quantity slippage：要求 market 权限；确认时把当前报价与相对滑点转为固定绝对边界。
- cancel orderId：只允许订单账户本人，管理员强撤使用独立 admin orders 路径。
- deposit/withdraw money|item：分别要求 deposit/withdraw。
- orders、assets、history：打开对应页。
- admin：进入 Task 8 的管理路由。

SubCommandExchange implements CommandHandler<Player> 并由：

~~~java
CommandContainer.builder()
    .prefix("exchange")
    .permission("quickshop.exchange.use")
    .description(locale -> messages.component("command-description", locale))
    .executor(new SubCommandExchange(router))
    .build()
~~~

注册到 quickShop.getCommandManager()。QseAliasCommand implements TabExecutor，把 label 后 args 原样交给同一 router；Main 通过 Objects.requireNonNull(getCommand("qse")).setExecutor(alias) 注册。

ExchangeRuntime 保存返回的 CommandContainer；close 的第一步调用 quickShop.getCommandManager().unregisterCmd(container)，并把 qse executor/tabCompleter 设为 null，确保排队关闭期间不会再进入新写请求。

- [ ] **Step 5: 运行命令测试**

Run: mvn -pl addon/exchange -Dtest=ExchangeCommandRouterTest test

Expected: PASS；限价和市价权限独立，确认动作生成 requestId，重复 GUI 点击复用确认上下文中的同一 requestId。

- [ ] **Step 6: 提交命令与权限**

~~~bash
git add addon/exchange/src/main/resources/plugin.yml addon/exchange/src/main/resources/messages.yml addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/AddonMessageService.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command
git commit -m "feat(exchange): expose player commands and permissions"
~~~

### Task 5: 实现行情快照、1 分钟 OHLCV 和 24 小时 ticker

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketQuote.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/Candle.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/CandleAggregator.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataService.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository/ExchangeRepository.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/marketdata/CandleAggregatorTest.java

- [ ] **Step 1: 写 OHLCV、成交额和受保护盘口红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.marketdata;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CandleAggregatorTest {
  @Test
  void aggregatesOneMinuteOhlcvAndNotional() {
    CandleAggregator aggregator = new CandleAggregator();
    aggregator.record("diamond-usd", new BigDecimal("100.00"), 2,
        Instant.parse("2026-07-26T00:00:10Z"));
    aggregator.record("diamond-usd", new BigDecimal("110.00"), 3,
        Instant.parse("2026-07-26T00:00:40Z"));
    Candle candle = aggregator.snapshot("diamond-usd",
        Instant.parse("2026-07-26T00:00:00Z")).orElseThrow();
    assertThat(candle.open()).isEqualByComparingTo("100.00");
    assertThat(candle.high()).isEqualByComparingTo("110.00");
    assertThat(candle.low()).isEqualByComparingTo("100.00");
    assertThat(candle.close()).isEqualByComparingTo("110.00");
    assertThat(candle.volume()).isEqualTo(5);
    assertThat(candle.notional()).isEqualByComparingTo("530.00");
  }
}
~~~

- [ ] **Step 2: 运行并确认行情类型缺失**

Run: mvn -pl addon/exchange -Dtest=CandleAggregatorTest test

Expected: FAIL，编译器报告 CandleAggregator 不存在。

- [ ] **Step 3: 定义行情值并聚合 UTC 分钟**

~~~java
package com.ghostchu.quickshop.addon.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record Candle(String marketId, Instant bucketStart,
                     BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                     long volume, BigDecimal notional) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record MarketQuote(String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
                          BigDecimal bestBid, BigDecimal bestAsk,
                          BigDecimal change24h, long volume24h, BigDecimal notional24h,
                          MarketStatus status, Instant asOf) {}
~~~

CandleAggregator 以 epochSecond / 60 × 60 作为 bucketStart；第一笔设置 OHLC，之后 high=max、low=min、close=latest、volume 使用 Math.addExact、notional 加 price×quantity。分钟切换时通过 repository.upsertCandle 持久化。

ExchangeRepository 在此任务增加：

~~~java
void upsertCandle(com.ghostchu.quickshop.addon.exchange.marketdata.Candle candle)
    throws java.sql.SQLException;
java.util.List<com.ghostchu.quickshop.addon.exchange.marketdata.Candle> loadCandles(
    String marketId, java.time.Instant fromInclusive, java.time.Instant toExclusive)
    throws java.sql.SQLException;
~~~

- [ ] **Step 4: 实现 MarketDataService**

MarketDataService：

- 从 MatchingEngine 成交事件逐笔更新 lastPrice、ReferencePriceTracker、CandleAggregator。
- bestBid/bestAsk 使用 OrderBook.bestExecutable(side, price -> limits.insideCage(price, reference))，只返回当前价格笼子内档位。
- depth() 同时返回全部档位和 executable 标志，让受保护档位仍显示但不被市价报价使用。
- ticker24h 从 candles_1m 加上当前未封口分钟计算开盘价、变化、volume、notional。
- 玩家订阅事件最多每 20 ticks 推送一次；内部审计消费者仍接收逐笔事件。

- [ ] **Step 5: 运行行情和风险测试**

Run: mvn -pl addon/exchange -Dtest=CandleAggregatorTest,MarketRiskTest test

Expected: PASS；OHLCV 和成交额正确，笼子外档位保留在完整深度但不成为 best executable quote。

- [ ] **Step 6: 提交行情服务**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/repository addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/marketdata
git commit -m "feat(exchange): publish quotes and one-minute candles"
~~~

### Task 6: 实现市场列表、详情和下单确认 GUI

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenu.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeViewService.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketListPresenter.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketRow.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/PageHandler.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketListPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MarketDetailPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/OrderEntryPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/OrderConfirmation.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/OrderConfirmationTest.java

- [ ] **Step 1: 写市价固定边界、冻结额和费用展示红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmationTest {
  @Test
  void freezesAbsoluteBoundaryAtConfirmationTime() {
    OrderConfirmation confirmation = OrderConfirmation.market(
        OrderSide.BUY, "diamond-usd", 5, new BigDecimal("100.00"),
        new BigDecimal("0.05"), new BigDecimal("0.002"), new BigDecimal("0.01"), 2, 2);
    assertThat(confirmation.slippageBoundary()).isEqualByComparingTo("105.00");
    assertThat(confirmation.maximumNotional()).isEqualByComparingTo("525.00");
    assertThat(confirmation.maximumFee()).isEqualByComparingTo("1.05");
    assertThat(confirmation.maximumFrozenCurrency()).isEqualByComparingTo("526.05");
  }
}
~~~

- [ ] **Step 2: 运行并确认 GUI 模型缺失**

Run: mvn -pl addon/exchange -Dtest=OrderConfirmationTest test

Expected: FAIL，编译器报告 OrderConfirmation 不存在。

- [ ] **Step 3: 实现纯 Java 确认模型**

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import java.math.*;
import java.util.UUID;

public record OrderConfirmation(
    UUID requestId, OrderSide side, String marketId, long quantity,
    BigDecimal slippageBoundary, BigDecimal maximumNotional,
    BigDecimal maximumFee, BigDecimal maximumFrozenCurrency) {

  public static OrderConfirmation market(OrderSide side, String marketId, long quantity,
                                         BigDecimal bestExecutablePrice, BigDecimal slippage,
                                         BigDecimal takerFeeRate, BigDecimal tickSize,
                                         int priceScale, int currencyScale) {
    BigDecimal multiplier = side == OrderSide.BUY
        ? BigDecimal.ONE.add(slippage) : BigDecimal.ONE.subtract(slippage);
    BigDecimal rawBoundary = bestExecutablePrice.multiply(multiplier);
    RoundingMode tickRounding =
        side == OrderSide.BUY ? RoundingMode.DOWN : RoundingMode.UP;
    BigDecimal boundary = rawBoundary.divide(tickSize, 0, tickRounding)
        .multiply(tickSize).setScale(priceScale);
    BigDecimal notional = boundary.multiply(BigDecimal.valueOf(quantity));
    BigDecimal fee = notional.multiply(takerFeeRate).setScale(currencyScale, RoundingMode.UP);
    BigDecimal frozen = side == OrderSide.BUY ? notional.add(fee) : BigDecimal.ZERO;
    return new OrderConfirmation(UUID.randomUUID(), side, marketId, quantity,
        boundary, notional, fee, frozen);
  }
}
~~~

确认页创建一次 requestId；双击确认按钮重复提交同一个 OrderConfirmation，不重新生成边界或 requestId。盘口变化不能扩大绝对边界。

- [ ] **Step 4: 注册七页 ExchangeMenu**

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import net.tnemc.menu.core.Menu;
import net.tnemc.menu.core.Page;

public final class ExchangeMenu extends Menu {
  public static final String NAME = "qs:exchange";

  public ExchangeMenu(ExchangeViewService views) {
    this.name = NAME;
    this.title = "QuickShop Exchange";
    this.rows = 6;
    add(1, new MarketListPage(views));
    add(2, new MarketDetailPage(views));
    add(3, new OrderEntryPage(views));
  }

  private void add(int number, PageHandler handler) {
    Page page = new Page(number);
    page.setOpen(handler::open);
    addPage(page);
  }
}
~~~

ExchangeRuntime.start 在恢复完成后、acceptingWrites=true 之前执行：

~~~java
net.tnemc.menu.core.manager.MenuManager.instance()
    .addMenu(new ExchangeMenu(viewService));
~~~

同时注册 GuiRefreshCoordinator 的退出/关闭监听。插件关闭先取消所有 Exchange viewer 刷新和 GuiChatInputManager 中属于 qs:exchange 的输入上下文，再关闭后台服务；不得关闭 QuickShop 全局 MenuManager。

PageHandler 使用以下完整定义；MarketListPresenter 把 MarketQuote 映射为不可变 MarketRow，页面只消费 rows，使用 IconBuilder/DataAction/SwitchPageAction，与 QuickShop ShopBrowseMenu 的 TNML 模式一致。

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;

public record MarketRow(String marketId, String displayName,
                        BigDecimal lastPrice, BigDecimal bestBid, BigDecimal bestAsk,
                        BigDecimal change24h, long volume24h, MarketStatus status) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import net.tnemc.menu.core.callbacks.page.PageOpenCallback;

@FunctionalInterface
public interface PageHandler {
  void open(PageOpenCallback callback);
}
~~~

- [ ] **Step 5: 实现列表、详情和下单交互**

- 列表显示 latest、best bid/ask、24h change、volume、status；HALTED/PAUSED 用不同材质且仍可查询。
- 详情显示完整深度、近期成交、玩家持仓与开放订单；笼子外档位 lore 标记 protected。
- 下单页使用 GuiChatInputManager 收集数量/价格/滑点，解析为 BigDecimal/long 后交给纯 Java validator。
- 市价滑点未填写用 5%，管理员允许的输入最大为 20%。
- 确认页显示预计成交档位、最大冻结、Maker/Taker 费率和最坏金额；按钮权限再次检查并提交固定 requestId。
- 所有 SQL/view 查询通过后台 CompletableFuture；完成后用 QuickShop.folia().getScheduler().runAtEntityLater(player, render, 1) 切回玩家上下文。

- [ ] **Step 6: 运行 GUI 模型和命令回归**

Run: mvn -pl addon/exchange -Dtest=OrderConfirmationTest,ExchangeCommandRouterTest test

Expected: PASS；100.00 买入报价在 5% 滑点下固定为 105.00，最大冻结 526.05，重复确认不扩大边界。

- [ ] **Step 7: 提交市场和下单 GUI**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui
git commit -m "feat(exchange): add market and order menus"
~~~

### Task 7: 实现我的订单、资产、历史和 1Hz 刷新

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/MyOrdersPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AssetsPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/HistoryPage.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/AdminPage.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenu.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/GuiRefreshCoordinator.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/PlayerUiScheduler.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/GuiRefreshCoordinatorTest.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/MutableClock.java

- [ ] **Step 1: 写每玩家最多 1Hz、退出即取消红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GuiRefreshCoordinatorTest {
  @Test
  void coalescesUpdatesToOnePerSecondAndStopsAfterClose() {
    MutableClock clock = new MutableClock(Instant.EPOCH);
    GuiRefreshCoordinator refresh = new GuiRefreshCoordinator(clock, Duration.ofSeconds(1));
    UUID player = UUID.randomUUID();
    AtomicInteger renders = new AtomicInteger();
    refresh.subscribe(player, renders::incrementAndGet);
    refresh.marketChanged();
    refresh.marketChanged();
    refresh.tick();
    assertThat(renders).hasValue(1);
    refresh.marketChanged();
    refresh.tick();
    assertThat(renders).hasValue(1);
    refresh.unsubscribe(player);
    clock.advance(Duration.ofSeconds(1));
    refresh.tick();
    assertThat(renders).hasValue(1);
  }
}
~~~

- [ ] **Step 2: 运行并确认刷新协调器缺失**

Run: mvn -pl addon/exchange -Dtest=GuiRefreshCoordinatorTest test

Expected: FAIL，编译器报告 GuiRefreshCoordinator 不存在。

- [ ] **Step 3: 实现合并刷新**

GuiRefreshCoordinator 保存 subscribed viewer、lastRender 和 dirty 标志；marketChanged 只设 dirty；每 20 ticks 的异步 tick 找出满一秒的 dirty viewer，再通过注入的 PlayerUiScheduler 调度 render。PlayerQuitEvent、InventoryCloseEvent 和插件关闭都调用 unsubscribe；不得保存 Bukkit Inventory 到后台线程。

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import java.time.*;

final class MutableClock extends Clock {
  private Instant instant;
  MutableClock(Instant instant) { this.instant = instant; }
  void advance(Duration duration) { instant = instant.plus(duration); }
  @Override public ZoneId getZone() { return ZoneOffset.UTC; }
  @Override public Clock withZone(ZoneId zone) { return this; }
  @Override public Instant instant() { return instant; }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.UUID;

@FunctionalInterface
public interface PlayerUiScheduler {
  void execute(UUID playerId, Runnable render);
}
~~~

- [ ] **Step 4: 完成四个页面**

- MyOrdersPage：OPEN/PARTIALLY_FILLED 在前，可撤单；历史状态只读。修改订单按钮实际执行 cancel old + place new，并展示失去时间优先级警告。
- AssetsPage：每币种 available/frozen、每市场 item available/frozen、money/item deposit/withdraw、PREPARED 待领取和 REVIEW_REQUIRED 提示。
- HistoryPage：订单、成交、Maker/Taker 手续费、资金和物品 journal 分页；不允许历史修改。
- AdminPage：只有任一相应 admin 权限时可见；每个图标再次检查 market/orders/recovery/audit 独立权限。

四个页面完成后在 ExchangeMenu 构造器中追加：

~~~java
add(4, new MyOrdersPage(views));
add(5, new AssetsPage(views));
add(6, new HistoryPage(views));
add(7, new AdminPage(views));
~~~

所有列表查询必须带 player/account 条件和 limit/offset；默认每页 36 条，绝不在游戏线程加载全历史。

- [ ] **Step 5: 运行 UI 刷新与完整 GUI 模型测试**

Run: mvn -pl addon/exchange -Dtest=GuiRefreshCoordinatorTest,OrderConfirmationTest test

Expected: PASS；连续行情事件合并为每秒一次玩家渲染，退出后无调度任务。

- [ ] **Step 6: 提交资产和历史 GUI**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/GuiRefreshCoordinatorTest.java
git commit -m "feat(exchange): add account menus and throttled refresh"
~~~

### Task 8: 实现管理操作、审计、核对和箱子商店共存策略

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeService.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AuditRecord.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/AuditExporter.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouter.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ContainerShopPolicyListener.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/AdminExchangeServiceTest.java

- [ ] **Step 1: 写暂停、强撤、恢复补偿和核对失败红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.operations;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExchangeServiceTest {
  @Test
  void forceCancelAppendsAuditAndNeverMutatesHistory() throws Exception {
    AdminFixture fixture = AdminFixture.openOrder();
    UUID actor = UUID.randomUUID();
    fixture.admin().forceCancel(actor, fixture.orderId(), "suspected abuse");
    assertThat(fixture.orderStatus()).isEqualTo("CANCELLED");
    assertThat(fixture.openOrderReservation()).isZero();
    assertThat(fixture.auditRecords()).singleElement().satisfies(record -> {
      assertThat(record.actorId()).isEqualTo(actor);
      assertThat(record.reason()).isEqualTo("suspected abuse");
      assertThat(record.beforeState()).contains("OPEN");
      assertThat(record.afterState()).contains("CANCELLED");
    });
    assertThat(fixture.tradeHistoryMutations()).isZero();
  }

  @Test
  void reconciliationDifferencePausesAffectedMarket() throws Exception {
    AdminFixture fixture = AdminFixture.withLedgerDifference("diamond-usd");
    fixture.admin().reconcile(UUID.randomUUID(), "daily reconciliation");
    assertThat(fixture.marketStatus()).isEqualTo("PAUSED");
    assertThat(fixture.highestSeverityAlerts()).isEqualTo(1);
  }
}
~~~

- [ ] **Step 2: 运行并确认运维服务缺失**

Run: mvn -pl addon/exchange -Dtest=AdminExchangeServiceTest test

Expected: FAIL，编译器报告 AdminExchangeService 不存在。

- [ ] **Step 3: 实现只追加审计的管理动作**

~~~java
package com.ghostchu.quickshop.addon.exchange.operations;

import java.time.Instant;
import java.util.UUID;

public record AuditRecord(UUID auditId, UUID actorId, String action, String targetId,
                          String reason, String beforeState, String afterState,
                          Instant createdAt) {}
~~~

AdminExchangeService 提供：

- create/enable/pause/resume/close market；close 前事务撤销全部开放订单并解冻。
- forceCancel；复用普通撤单结算，不修改成交历史。
- reconcile；差异非零立即 PAUSED 受影响市场并插入最高级 alert。
- resolveReview；管理员必须填写外部证据和处理结论。确认外部成功时在同一事务追加必要 journal/内部资产变化并将原记录 REVIEW_REQUIRED→COMPLETED；确认外部失败时释放预留并将原记录 REVIEW_REQUIRED→FAILED。两种结果都写不可修改的 AuditRecord，禁止再次调用外部经济或背包 API。
- exportAudit；按时间范围流式写 UTF-8 CSV 到 plugin data folder/audit，文件名只由 UTC 时间和随机 UUID 构成，防止路径注入。

每个动作在同一事务内写 actor、时间、reason、before、after；reason 去首尾空格后至少 8 字符。

- [ ] **Step 4: 实现管理员路由**

AdminCommandRouter 精确子命令：

~~~text
/qse admin market create|enable|pause|resume|close <marketId> <reason>
/qse admin order cancel <orderId> <reason>
/qse admin transfer review <transferId> confirm-external-success|confirm-external-failure <reason>
/qse admin audit reconcile <reason>
/qse admin audit export <fromEpoch> <toEpoch>
~~~

market、orders、recovery、audit 分别检查四个独立权限。所有管理写请求生成 requestId 并审计；终端输出只报告结果 ID，不输出玩家敏感资产详情给无 audit 权限者。

- [ ] **Step 5: 阻止被配置市场的普通箱子商店**

~~~java
package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.api.event.Phase;
import com.ghostchu.quickshop.api.event.management.ShopCreateEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class ContainerShopPolicyListener implements Listener {
  private final MarketRegistry markets;

  public ContainerShopPolicyListener(MarketRegistry markets) {
    this.markets = markets;
  }

  @EventHandler(ignoreCancelled = true)
  public void onCreate(ShopCreateEvent event) {
    if (!event.isPhase(Phase.PRE_CANCELLABLE) || event.shop().isEmpty()) return;
    var item = event.shop().orElseThrow().getItem();
    if (markets.blocksContainerShop(item)) {
      event.setCancelled(true, Component.text("This item is exchange-only."));
    }
  }
}
~~~

blockContainerShops 默认 false；切换为 true 不删除、不迁移、不自动取消已有箱子商店，只阻止之后创建。

- [ ] **Step 6: 运行管理和共存策略测试**

Run: mvn -pl addon/exchange -Dtest=AdminExchangeServiceTest,ContainerShopPolicyListenerTest test

Expected: PASS；强撤释放资产并追加审计；核对差异暂停市场；配置默认不影响已有 QuickShop 商店。

- [ ] **Step 7: 提交管理运维**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouter.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ContainerShopPolicyListener.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations
git commit -m "feat(exchange): add audited administration"
~~~

### Task 9: 实现可观测性和异常交易告警

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/ExchangeMetrics.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/MetricSnapshot.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations/SuspiciousTradingDetector.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations/SuspiciousTradingDetectorTest.java

- [ ] **Step 1: 写告警不自动处罚和指标快照红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.operations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuspiciousTradingDetectorTest {
  @Test
  void emitsAlertWithoutChangingAccountsOrOrders() {
    OperationsFixture fixture = OperationsFixture.repeatedTwoWayTrades();
    fixture.detector().scan(fixture.events());
    assertThat(fixture.alerts()).anyMatch(alert ->
        alert.type().equals("HIGH_FREQUENCY_RECIPROCAL_TRADING"));
    assertThat(fixture.accountMutations()).isZero();
    assertThat(fixture.orderMutations()).isZero();
  }
}
~~~

- [ ] **Step 2: 运行并确认观测类型缺失**

Run: mvn -pl addon/exchange -Dtest=SuspiciousTradingDetectorTest test

Expected: FAIL，编译器报告 SuspiciousTradingDetector 不存在。

- [ ] **Step 3: 记录核心指标**

ExchangeMetrics 使用 LongAdder、AtomicLong 和有界延迟直方图，提供不可变 MetricSnapshot：

- 每市场 queue length、matching latency p50/p95/p99。
- SQL commit latency p50/p95/p99 和 failure count。
- open orders、trade volume/notional、reject reason counts。
- breaker count/duration。
- PREPARED/PROCESSING/REVIEW_REQUIRED transfer counts。
- reconciliation differences。
- GUI request count 和 player-thread callback p95。

不得把 UUID、玩家名、订单 ID 写入指标标签，防止高基数。/qse admin audit status 显示聚合快照；服务器日志每 5 分钟输出一次摘要。

- [ ] **Step 4: 实现只告警检测器**

SuspiciousTradingDetector 在异步只读快照上检测：

- 5 分钟内同两账户高频双向成交。
- cancel/place 比率超过配置阈值且样本至少 20。
- 多账户在 2 秒窗口同步同向/反向操作。
- 单账户持仓或成交量超过市场 24h 规模的配置比例。

每项只向 exchange_audit_alerts 插入 evidence JSON、severity 和时间；不撤单、不冻结、不处罚。相同 type/market/account 在 10 分钟内用内存去重键抑制重复告警。

- [ ] **Step 5: 运行观测与告警测试**

Run: mvn -pl addon/exchange -Dtest=SuspiciousTradingDetectorTest,ExchangeMetricsTest test

Expected: PASS；已知可疑序列产生告警，账户和订单状态完全不变，指标不含玩家 ID 标签。

- [ ] **Step 6: 提交可观测性**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/operations addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/operations
git commit -m "feat(exchange): add metrics and audit alerts"
~~~

### Task 10: 端到端验证、性能门槛和上线手册

**Files:**
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ExchangeEndToEndIT.java
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ExchangeLoadIT.java
- Create: docs/exchange-operations.md

- [ ] **Step 1: 写端到端业务流测试**

~~~java
package com.ghostchu.quickshop.addon.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeEndToEndIT {
  @Test
  void depositsTradesRestartsCancelsAndWithdrawsWithoutAssetDrift() throws Exception {
    EndToEndFixture server = EndToEndFixture.sqlite();
    var seller = server.playerWithDiamonds(64);
    var buyer = server.playerWithMoney("10000.00");
    server.depositItems(seller, 32);
    server.depositMoney(buyer, "5000.00");
    var sell = server.limitSell(seller, "100.00", 20);
    var buy = server.marketBuy(buyer, 12, "0.05");
    server.restartRuntime();
    server.cancel(seller, sell.orderId());
    server.withdrawItems(buyer, 12);
    server.withdrawMoney(seller);

    assertThat(buy.filledQuantity()).isEqualTo(12);
    assertThat(server.reconcile().balanced()).isTrue();
    assertThat(server.duplicateExternalOperations()).isZero();
    assertThat(server.negativeBalances()).isZero();
  }
}
~~~

- [ ] **Step 2: 运行端到端测试并修正所有集成缝隙**

Run: mvn -pl addon/exchange -Dtest=ExchangeEndToEndIT test

Expected: PASS；充值→成交→重启→撤单→提现后账本、托管、冻结和外部调用全部一致。

- [ ] **Step 3: 写参考容量负载测试**

ExchangeLoadIT 创建 100 个市场和总计 100,000 个开放订单，预热 30 秒后持续 60 秒提交 200 operations/s，采集：

- core match P95 < 20ms。
- 本地 MySQL commit-inclusive confirmation P95 < 100ms。
- queue backlog 在停止注入后 10 秒内归零。
- 模拟 PlayerUiScheduler callback P95 < 5ms。

测试以 @Tag("load") 标记，默认 verify 运行确定性小样本；完整负载通过明确命令运行，防止普通开发机每次构建耗时过长。

- [ ] **Step 4: 运行完整测试和负载基线**

Run: mvn -pl addon/exchange -am clean verify

Expected: BUILD SUCCESS，所有确定性与 SQLite 集成测试通过。

Run: mvn -pl addon/exchange -Dgroups=load -Dtest=ExchangeLoadIT test

Expected: 在 4 CPU、同机 MySQL 参考环境达到 100 市场、100,000 开放订单、200 operations/s 和既定 P95；测试输出 JSON 报告到 addon/exchange/target/exchange-load-report.json。

- [ ] **Step 5: 编写部署和恢复手册**

docs/exchange-operations.md 必须写明可直接执行的顺序：

1. 备份 QuickShop 数据库和 plugins/qssuite-exchange。
2. 保持 enabled=false 启动一次，检查配置生成但不创建市场订单。
3. 测试服只启用测试货币和少量普通材料，完成 Paper 与 Folia 各一次存取/成交/重启测试。
4. 正式服启用 whitelist，设置低持仓/订单限额，观察至少一个完整经济周期。
5. 每日执行 reconcile，检查 REVIEW_REQUIRED、breaker、SQL P95 和 custody 差额。
6. 扩大白名单，再逐市场提高限额；不迁移、不导入、不取消已有箱子商店。
7. 数据库故障时 PAUSED/RECOVERING，不手工修改 orders/trades/ledger 表。
8. REVIEW_REQUIRED 只通过管理补偿流程处理并保存外部经济/背包证据。
9. 紧急停机后保留开放订单；重启必须先恢复 order book 后开放写入。
10. 跨服前保持只有一个撮合插件实例；共享 MySQL 的第二实例因 writer lock 启动失败。

- [ ] **Step 6: 在 Paper 和 Folia 测试服做手工验收**

Paper：

~~~text
1. enabled=true，白名单只加入两个测试账号。
2. /qse 存入资金和 DIAMOND。
3. 两账号各挂一笔交叉限价单，确认 Maker 价格和两边手续费。
4. 建一笔部分成交单，重启，确认原 prioritySequence 和余量不变。
5. 填满背包提现，确认转账留在待领取且地面无掉落。
~~~

Folia：

~~~text
1. 重复 Paper 流程。
2. 两玩家位于不同 region 同时打开 GUI 和提交订单。
3. 检查日志无 AsyncCatcher/region ownership 异常。
4. 用 profiler 确认玩家线程回调 P95 < 5ms。
~~~

- [ ] **Step 7: 提交端到端验证与手册**

~~~bash
git add addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ExchangeEndToEndIT.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ExchangeLoadIT.java docs/exchange-operations.md
git commit -m "test(exchange): verify end-to-end rollout"
~~~

## Phase 4 与最终验收

Run: mvn -pl addon/exchange -am clean verify

Expected: BUILD SUCCESS。

Run: rg -n "double|float" addon/exchange/src/main/java

Expected: 资产、价格、费用和余额不使用 double/float；若性能指标使用 double，必须局限在 operations 指标包且不能回流领域计算。

Run: rg -n "Bukkit|ItemStack|Player|Inventory" addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core

Expected: 无输出，撮合领域仍可迁出 Minecraft 进程。

Run: git status --short

Expected: 只显示本计划产生的 addon/exchange、pom.xml 和 docs 文件；不得夹带开始任务前已存在的 README.md、EULA.md、AGENTS.md 或 .github 工作树改动。
