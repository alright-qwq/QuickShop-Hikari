# Exchange Chart, Clock and Handbook Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 QuickShop-Hikari Exchange 实现专业行情地图图表、可配置时区的 GUI 时钟，以及可通过 `/qse book` 领取并右键打开交易页面的防伪交易手册。

**Architecture:** 保持撮合与结算核心不变。行情图增强放在纯 Java 展示层，并由 `MarketDataService` 暴露只读实时 Candle 快照；GUI 时钟通过不可变配置和可注入 `Clock` 进入公共 Chrome；交易手册由独立 Bukkit 平台服务管理 PDC 物品，命令路由只调用领取能力，交互监听器只负责验证并调度打开现有菜单。

**Tech Stack:** Java 21、Maven、JUnit 5、AssertJ、MockBukkit/Paper API 1.21.1、FoliaLib 0.5.1、TNML、Adventure、Bukkit PDC、纯 Java byte 像素渲染。

---

## 固定约束

- 工作目录：`.worktrees/exchange-safety-review`。
- 该工作树包含此前已测试并交付但尚未提交的 Exchange 安全、GUI 兼容和行情展示改动；不得 reset、checkout 或覆盖这些修改。
- 所有功能先写失败测试，再写最小实现。
- 不引入 AWT，不按秒刷新 GUI，不在非玩家所有者线程访问背包或打开菜单。
- 中国行情颜色保持上涨红、下跌绿。

### Task 1: 实时 Candle 快照与展示数据合并

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSource.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java:167-181`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/marketdata/MarketDataServiceTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSourceTest.java`

**Step 1: Write failing live snapshot tests**

新增测试：`MarketDataService.liveCandles(marketId, from, to)` 返回不可变、按分钟排序、限定窗口的当前内存 Candle，且不暴露其他市场。

**Step 2: Run RED**

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -o -pl addon/exchange -am "-Dtest=MarketDataServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation failure because `liveCandles` does not exist.

**Step 3: Implement minimal read-only snapshot**

在 `MarketDataService` 增加：

```java
public List<Candle> liveCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
  Objects.requireNonNull(...);
  return candles.snapshots(marketId, fromInclusive, toExclusive);
}
```

保持 `CandleAggregator.snapshots(...)` 的同步与不可变语义。

**Step 4: Write failing merge tests**

扩展 `MarketAccess`，加入实时 Candle reader。验证：

- repository 和 live 的不同分钟均保留；
- 相同 `bucketStart` 时 live 覆盖 persisted；
- 输出按时间排序；
- 重复分钟不会累加两次 volume。

**Step 5: Implement merge**

在 `ExchangeMarketDisplayDataSource.snapshot(...)` 使用 `TreeMap<Instant, Candle>`：先放持久化，再放 live，最后复制为排序列表。给 `MarketAccess` 保留三参数兼容构造器，默认 live reader 返回空列表，减少测试和调用点破坏。

**Step 6: Wire runtime**

`ExchangeRuntimeFactory` 的 `MarketAccess` 传入：

```java
(from, to) -> marketData.liveCandles(entry.getKey(), from, to)
```

**Step 7: Run GREEN**

运行上述两组测试，Expected: PASS。

### Task 2: 专业行情图布局和调色板

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayout.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/PixelFont.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartPalette.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayService.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartLayoutTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRendererTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayServiceTest.java`

**Step 1: Write failing layout tests**

对 1x1、2x1、2x2 验证：header、plot、priceAxis、timeAxis、volume 区域均在画布内且不重叠；1x1 可隐藏低优先级文字，但 plot 宽高必须达到安全下限。

**Step 2: Run RED**

运行 `MarketChartLayoutTest`，Expected: compilation failure。

**Step 3: Implement `MarketChartLayout`**

使用不可变 record 表示矩形。按像素宽高选择 compact/wide/full 密度，不暴露任意布局配置。

**Step 4: Write failing renderer feature tests**

验证新增调色板像素实际出现：

- `LATEST_PRICE` 虚线；
- `VOLUME_RISE` / `VOLUME_FALL` 柱；
- 单点模式 `HIGHLIGHT` 多于一个像素；
- 三种尺寸都有主网格和价格轴；
- KLINE/LINE 仍使用涨红跌绿；
- 所有写入均不越界。

**Step 5: Implement tiny pixel font**

只实现图表需要的字符集：数字、`.`、`-`、`+`、`%`、`:`、`/`、ASCII 大写字母和空格。字体采用 3x5 或 4x6 位图，未知字符显示为空白，避免 AWT。

**Step 6: Refactor renderer around layout**

把固定 `LEFT/RIGHT/TOP/BOTTOM` 替换为布局区域；实现柔和横纵网格、价格 padding、最新价虚线/标签、时间刻度、成交量柱、摘要和单点高亮。保留旧 `render(series, mode, dimensions)` 作为兼容入口；新增接收显示名、周期和 quote 的渲染上下文，由 `MarketDisplayService` 使用。

**Step 7: Run renderer GREEN and regressions**

运行 `MarketChartLayoutTest,MarketChartRendererTest,MarketChartSlicesTest,MarketDisplayServiceTest`，Expected: PASS。

### Task 3: 图表配置开关

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartOptions.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactoryTest.java`

**Step 1: Write failing option validation tests**

验证默认值全部开启，旧配置缺失时安全使用默认值。

**Step 2: Implement immutable options and wiring**

读取：

```yaml
displays.chart.professional-layout
displays.chart.include-live-candle
displays.chart.show-volume
displays.chart.show-latest-price-line
```

`include-live-candle=false` 时 runtime 给 live reader 传空结果；其他选项交给 renderer。

**Step 3: Run tests GREEN**

运行 runtime 和 display 定向测试。

### Task 4: GUI 时钟配置模型

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeClockDisplay.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeClockDisplayTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ModuleSmokeTest.java`

**Step 1: Write failing formatter tests**

固定 `Clock.fixed(Instant.parse("2026-07-31T04:30:00Z"), ZoneOffset.UTC)`，验证 `Asia/Shanghai` 输出 `2026-07-31 12:30`；关闭时返回 empty；无效 zone/format 使用回退值并通过警告 consumer 报告一次。

**Step 2: Run RED**

Expected: `ExchangeClockDisplay` missing。

**Step 3: Implement validated immutable display**

工厂方法接收 enabled、zoneId、pattern、Clock 和 warning consumer。内部保存 `ZoneId` 与 `DateTimeFormatter`，暴露 `Optional<DisplayTime> now()`。

**Step 4: Wire config**

在 `Main.registerPlayerEntrypoints()` 创建时钟配置并传给 `ExchangeMenuService`。默认：enabled true、`Asia/Shanghai`、`yyyy-MM-dd HH:mm`。

### Task 5: 公共 GUI Chrome 显示时间

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuService.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenu.java`
- Modify: all page constructors under `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/` that create `ExchangeMenuChrome`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuChrome.java`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeMenuChromeTest.java`

**Step 1: Write failing Chrome test**

用固定时间配置调用 `prepare(...)`，验证 slot 7 是 `CLOCK`，slot 4 标题、slot 8 帮助、底部导航仍存在。关闭配置时 slot 7 保持边框。

**Step 2: Implement constructor propagation**

给现有 public/service 构造器保留默认 overload，减少其他测试破坏。生产构造器显式传 `ExchangeClockDisplay`。

**Step 3: Add localized messages**

新增中英文：`ui-clock-title`、`ui-clock-zone`。

**Step 4: Run UI tests**

Expected: Chrome、Menu、Lifecycle 相关测试全部 PASS，且没有新增 scheduler。

### Task 6: 交易手册物品服务

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ExchangeHandbookSettings.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ExchangeHandbookService.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/ExchangeHandbookServiceTest.java`

**Step 1: Write failing item tests**

使用 MockBukkit 或可注入 item factory 验证：合法 PDC、普通改名书无效、重复识别、背包满不掉落、功能关闭拒绝、材质无效回退 `KNOWLEDGE_BOOK`。

**Step 2: Implement settings and service**

`ExchangeHandbookService` 负责 `createItem`、`isHandbook`、`claim(Player)`、`give(Player, boolean allowDuplicate)`。PDC 使用版本字符串 `v1`；玩家消息通过 `AddonMessageService`。

**Step 3: Run GREEN**

运行 service 测试，Expected: PASS。

### Task 7: `/qse book` 自助领取命令

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/CommandActor.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/BukkitCommandActor.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouterTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command/BukkitCommandActorTest.java`

**Step 1: Write failing router tests**

验证 `book`：先 rollout、再 `quickshop.exchange.use`、再调用 `actor.claimHandbook()`；不打开菜单；tab completion 含 `book`。

**Step 2: Add platform-neutral actor capability**

`CommandActor` 增加默认 `claimHandbook()`，默认发送 command invalid 或返回 unsupported；`BukkitCommandActor` 注入领取 action，在玩家所有者线程调用手册服务。

**Step 3: Wire production actor**

`Main` 构造 `ExchangeHandbookService`，创建 actor 时传入 claim action。

**Step 4: Run command tests GREEN**

Expected: router and actor tests PASS。

### Task 8: 管理员补发命令

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouter.java`
- Modify: `addon/exchange/src/main/resources/plugin.yml`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouterTest.java`

**Step 1: Write failing admin give tests**

命令：`/qse admin book give <player>`。验证权限 `quickshop.exchange.admin.handbook`、在线玩家查找、成功补发、目标不存在和 tab completion。

**Step 2: Implement minimal admin capability**

通过注入的 handbook giver 完成 Bukkit 玩家查找与补发；不要让纯 router 直接依赖 Bukkit 静态 API。

**Step 3: Add permission**

`plugin.yml` 新增 `quickshop.exchange.admin.handbook`，default op；`hasAnyAdminPermission` 纳入该权限。

**Step 4: Run admin tests GREEN**

### Task 9: 右键手册打开 Exchange

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ExchangeHandbookListener.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/ExchangeHandbookListenerTest.java`

**Step 1: Write failing interaction tests**

覆盖：主手 `RIGHT_CLICK_AIR/BLOCK` 合法手册只打开一次并取消事件；副手、左键、普通书不触发；功能关闭、无权限、rollout 拒绝仅消息；调度 action 由注入 scheduler 捕获。

**Step 2: Implement listener**

事件处理顺序：action → hand → PDC → cancel → enabled → permission → rollout → entity scheduler → `menus.open(player, "markets", 1)`。

**Step 3: Register and unregister safely**

`Main` 保存 listener 字段，注册到 Bukkit；关闭时与 menu listener 一起 `HandlerList.unregisterAll(...)`。

**Step 4: Run listener tests GREEN**

### Task 10: 文档、完整验证和产物

**Files:**
- Modify: `docs/exchange-operations.md`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Modify: `addon/exchange/src/main/resources/plugin.yml`
- Output: `outputs/Addon-Exchange-6.3.0.0-SNAPSHOT-11-professional-chart-clock-handbook.jar`

**Step 1: Update operations documentation**

记录专业图表尺寸差异、实时 Candle、GUI 时区配置、`/qse book`、管理员补发、权限与排障。

**Step 2: Run targeted tests**

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -o -pl addon/exchange -am "-Dtest=MarketChart*Test,ExchangeMarketDisplayDataSourceTest,MarketDataServiceTest,ExchangeClockDisplayTest,ExchangeMenuChromeTest,ExchangeHandbook*Test,ExchangeCommandRouterTest,AdminCommandRouterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: all selected tests PASS。

**Step 3: Run full verification**

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -o -pl addon/exchange -am verify
```

Expected: Reactor 7/7 SUCCESS, 0 failures, 0 errors。

**Step 4: Check diff and JAR**

```text
git diff --check
git status --short
```

复制构建 JAR 到指定 outputs 文件名，检查包含新增 class/config/messages/plugin.yml 且不含测试类。

**Step 5: Calculate SHA-256**

使用 PowerShell `Get-FileHash -Algorithm SHA256`，记录文件大小、哈希和实服验收命令。

**Step 6: Request code review**

在宣告完成前运行代码审查和 completion verification，处理所有 Critical/Important 问题并重新执行受影响测试。
