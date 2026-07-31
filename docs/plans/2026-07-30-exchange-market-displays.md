# Exchange Market Displays Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 QuickShop-Hikari Exchange 增加可悬挂在物品展示框中的 K 线/折线行情地图墙，以及显示现价和涨跌信息的受管理告示牌。

**Architecture:** 将实现分为无 Bukkit 依赖的纯 Java 图表核心、可持久化的展示绑定模型、Bukkit/Folia 世界更新适配层和管理员命令/保护事件层。历史 OHLCV 复用现有 `ExchangeRepository.loadCandles(...)`，实时报价复用 `PersistentOrderService.marketQuote(...)`；数据库查询与像素计算在后台执行，物品展示框更新通过实体所有者调度，告示牌更新通过区域所有者调度，未加载区块不会被强制加载。

**Tech Stack:** Java 21、Maven、JUnit 5、AssertJ、Paper API 1.21.1、FoliaLib 0.5.1、Bukkit YAML、`MapView`/`MapRenderer`/`MapCanvas`、`ItemFrame`、`Sign`。

---

## 固定设计决策

- 每个地图墙绑定一个 `marketId`，并可在 `KLINE` 与 `LINE` 两种模式间切换。
- 支持 `1x1`、`2x1`、`2x2`，默认推荐 `2x1`；每张原版地图为 128×128 像素。
- 行情周期第一版支持 `1h`、`6h`、`24h`、`7d`；数据源保持 UTC 分钟 candle，渲染前按像素宽度聚合。
- 中国行情配色固定为上涨红、下跌绿、平盘灰；背景使用浅色以保证原版地图可读性。
- 告示牌默认四行：市场显示名、现价、24h 涨跌、`买价 / 卖价`；停牌或关闭时第四行改为状态。
- 新增权限 `quickshop.exchange.admin.display`，默认仅 OP。
- 管理员通过注视目标物品展示框或告示牌进行绑定和管理，不要求手输世界坐标。
- 展示绑定持久化到插件数据目录 `displays.yml`；实体 UUID、地图 ID、世界和方块坐标都保存，以便重启恢复和安全判定。
- 普通玩家不能旋转、取下、破坏或覆盖受管理的展示框与告示牌。
- 第一版不强制加载区块；展示在区块再次加载后由监听器或下一刷新周期恢复。

### Task 1: 纯 Java 图表领域模型与行情聚合

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartMode.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartPeriod.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartDimensions.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ChartCandle.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeries.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilder.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilderTest.java`

**Step 1: Write failing enum and dimension parsing tests**

测试期望：

```java
assertThat(MarketChartMode.parse("kline")).isEqualTo(MarketChartMode.KLINE);
assertThat(MarketChartMode.parse("LINE")).isEqualTo(MarketChartMode.LINE);
assertThat(MarketChartPeriod.parse("24h").duration()).isEqualTo(Duration.ofHours(24));
assertThat(MarketChartDimensions.parse("2x1")).isEqualTo(new MarketChartDimensions(2, 1));
assertThatThrownBy(() -> MarketChartDimensions.parse("3x3"))
    .isInstanceOf(IllegalArgumentException.class);
```

**Step 2: Run tests and verify RED**

Run:

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -pl addon/exchange -am "-Dtest=MarketChartSeriesBuilderTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation failure because display model classes do not exist.

**Step 3: Implement minimal parsing models**

- `MarketChartMode`: `KLINE`, `LINE` and case-insensitive `parse`.
- `MarketChartPeriod`: `ONE_HOUR`, `SIX_HOURS`, `ONE_DAY`, `SEVEN_DAYS`, each carrying command token and `Duration`.
- `MarketChartDimensions`: validate only `(1,1)`, `(2,1)`, `(2,2)` and expose `pixelWidth()`/`pixelHeight()`.

**Step 4: Add failing aggregation tests**

覆盖：

- 输入按 `bucketStart` 排序；
- 多个一分钟 candle 聚合时 open 取第一根、close 取最后一根、high/low 取极值、volume/notional 累加；
- candle 数超过可绘制宽度时压缩到有界数量；
- 空输入返回空 series；
- 单 candle 和所有价格相等时仍产生非零价格范围。

**Step 5: Implement minimal series builder**

`MarketChartSeriesBuilder.build(List<Candle>, int maxPoints)` 输出不可变 `MarketChartSeries`，只处理数据清洗、排序、分桶和价格范围，不引用 Bukkit 类型。

**Step 6: Run tests and verify GREEN**

Run the Task 1 test command. Expected: PASS.

**Step 7: Commit checkpoint**

```bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSeriesBuilderTest.java
git commit -m "feat(exchange): add market chart series model"
```

Do not commit unrelated pre-existing worktree changes.

### Task 2: 地图像素渲染与墙面切片

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartPalette.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartImage.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSlices.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRendererTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartSlicesTest.java`

**Step 1: Write failing empty and flat-series render tests**

验证：

- 输出宽高与 `MarketChartDimensions` 一致；
- 无数据时返回完整安全占位图，不抛异常；
- 单点和平价 series 不发生除零；
- 输出像素数组长度严格为 `width * height`。

**Step 2: Run renderer tests and verify RED**

Expected: compilation failure because renderer types do not exist.

**Step 3: Implement base canvas and price transform**

- 纯 Java `byte[]` 像素缓冲区；
- 固定边距、边框、网格和价格区域；
- `priceToY` 对空范围/平价范围提供安全中线；
- 不引入 AWT，避免无头服务器和额外依赖问题。

**Step 4: Write failing color-direction tests**

验证：

- `close > open` 的 candle 主体包含上涨红；
- `close < open` 的 candle 主体包含下跌绿；
- `close == open` 使用平盘灰；
- 影线覆盖 low 到 high；
- 折线按相邻 close 连线并使用方向颜色。

**Step 5: Implement KLINE and LINE modes**

仅实现测试要求的线段、影线和 candle 实体；每个 candle 至少占一列，超宽数据由 Task 1 聚合。

**Step 6: Write failing slicing tests**

对带坐标编码的 256×128 与 256×256 图像验证：

- 切片数量分别为 2 和 4；
- 顺序固定为从左到右、从上到下；
- 每片严格 128×128；
- 边界像素不丢失、不交叉。

**Step 7: Implement slicing and verify GREEN**

Run:

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -pl addon/exchange -am "-Dtest=MarketChartRendererTest,MarketChartSlicesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS.

**Step 8: Commit checkpoint**

Commit only Task 2 files.

### Task 3: 展示绑定模型和 YAML 注册表

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/DisplayLocation.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MapWallBinding.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketSignBinding.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayBindings.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayRegistry.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayRegistryTest.java`

**Step 1: Write failing round-trip tests**

创建临时 `displays.yml`，保存后重新加载并验证：

- 地图墙 ID、marketId、模式、周期、尺寸；
- 每个 frame 的 UUID、地图 ID 和顺序；
- 告示牌世界 UUID、x/y/z、格式；
- 未知模式、周期、尺寸或缺失字段被跳过并记录诊断，而不是阻止插件启动。

**Step 2: Run registry tests and verify RED**

Expected: compilation failure because registry types do not exist.

**Step 3: Implement immutable binding records**

所有构造器执行 null、空字符串、尺寸和列表数量校验；地图数量必须等于 `columns * rows`。

**Step 4: Implement atomic YAML persistence**

- 读取 `maps.*` 与 `signs.*`；
- 写入同目录临时文件后原子替换，文件系统不支持原子移动时安全回退；
- 对单条损坏记录 fail-soft；
- 注册表修改方法同步保护内存快照，查询返回不可变副本。

**Step 5: Run tests and verify GREEN**

Expected: PASS, including malformed-record recovery.

**Step 6: Commit checkpoint**

Commit only Task 3 files.

### Task 4: 行情查询、地图渲染缓存和告示牌格式

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ui/ExchangeViewService.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplaySnapshot.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayDataSource.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSource.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketSignFormatter.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartCache.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/ExchangeMarketDisplayDataSourceTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketSignFormatterTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartCacheTest.java`

**Step 1: Write failing data-source tests**

验证单次异步快照同时获得：

- 市场显示名；
- `MarketQuote`；
- `[now-period, now)` 范围的 candle；
- repository 查询异常通过 failed future 传播，不阻塞主线程。

**Step 2: Implement bounded asynchronous view method**

在 `ExchangeViewService` 或独立 data source 中复用既有 `viewExecutor`，不得调用 `.join()` 于 Bukkit/Folia 所有者线程。

**Step 3: Write failing sign-format tests**

验证：

- 上涨使用红色，显示 `+x.xx%`；
- 下跌使用绿色，显示 `-x.xx%`；
- 平盘使用灰色；
- null last price 显示 `--`；
- OPEN 显示买卖价，HALTED/CLOSED 显示状态；
- 每行文本在 Minecraft 告示牌可读范围内安全截断。

**Step 4: Implement formatter**

输出四个 Adventure `Component` 或可在世界适配层转换的四行值，不在 formatter 中访问世界。

**Step 5: Write failing cache tests**

缓存键包含 marketId、模式、周期、尺寸及行情指纹；相同输入复用像素，行情变化或模式变化触发重绘；缓存有最大条数和关闭清理。

**Step 6: Implement cache and verify GREEN**

Run all Task 4 tests. Expected: PASS.

**Step 7: Commit checkpoint**

Commit only Task 4 files.

### Task 5: Bukkit/Paper/Folia 地图墙和告示牌运行时

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/DisplayScheduler.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/FoliaDisplayScheduler.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketMapRenderer.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayService.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayListener.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/ExchangeShutdown.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayServiceTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayListenerTest.java`
- Modify Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactoryTest.java`
- Modify Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeTest.java`

**Step 1: Write failing orchestration tests with fake scheduler**

验证：

- 刷新先后台获取快照和计算像素；
- 每个 `ItemFrame` 更新提交到 entity scheduler；
- 每个 `Sign` 更新提交到 region scheduler；
- 世界缺失、区块未加载、实体缺失时跳过且不强制加载；
- 同一绑定刷新进行中时合并为一次后续刷新；
- 关闭后拒绝新任务并等待/取消受控后台工作。

**Step 2: Implement scheduler port and service state machine**

将世界调度细节隔离到 `FoliaDisplayScheduler`；核心 service 仅依赖接口，便于单元测试。

**Step 3: Write failing map renderer adapter tests**

验证 `MarketMapRenderer` 只把缓存的 128×128 byte 像素复制到 `MapCanvas`，不做数据库访问或重计算。

**Step 4: Implement Bukkit map adapter**

创建地图时清除其他 renderer，设置不可追踪、固定视图，并绑定缓存像素 renderer。

**Step 5: Write failing protection and chunk-recovery tests**

覆盖：

- 受管理 frame 旋转、取物、破坏、爆炸和活塞影响被阻止；
- 受管理 sign 被破坏、活塞移动或外部编辑被阻止；
- OP 也必须先通过 display 管理命令解除绑定，避免误操作；
- 区块加载时仅刷新该区块内的绑定。

**Step 6: Implement listener and runtime wiring**

- 注册 listener；
- 在 `ExchangeRuntimeFactory` 创建 data source、registry、service 和 scheduler；
- 每秒行情更新不直接重绘，service 按配置的最小刷新间隔合并；
- 关闭顺序先停止刷新，再关闭执行器，最后保存 registry。

**Step 7: Run tests and verify GREEN**

Run Task 5 tests plus runtime tests. Expected: PASS.

**Step 8: Commit checkpoint**

Commit only Task 5 files.

### Task 6: 管理员 display 命令、权限和消息

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayAdministration.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/CommandActor.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/BukkitCommandActor.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/ExchangeCommandRouter.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/command/SubCommandExchange.java`
- Modify: `addon/exchange/src/main/resources/plugin.yml`
- Modify: `addon/exchange/src/main/resources/messages.yml`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/command/AdminCommandRouterTest.java`
- Create Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplayAdministrationTest.java`

**Step 1: Write failing permission and syntax tests**

支持命令：

```text
/qse admin display map create <marketId> [1x1|2x1|2x2] [kline|line] [1h|6h|24h|7d]
/qse admin display map mode <kline|line>
/qse admin display map period <1h|6h|24h|7d>
/qse admin display map refresh
/qse admin display map remove
/qse admin display sign bind <marketId>
/qse admin display sign refresh
/qse admin display sign remove
```

所有命令要求玩家身份、权限 `quickshop.exchange.admin.display` 和有效注视目标；控制台得到明确的“仅玩家可用”消息。

**Step 2: Implement actor target abstraction**

`BukkitCommandActor` 负责 ray trace 当前注视的 frame/sign，router 不直接依赖 Bukkit Player，测试使用 fake target。

**Step 3: Write failing administration tests**

验证：

- `create 2x1` 要求目标 frame 能按相邻平面解析出两个展示框；
- 所有 frame 必须为空或已有本功能管理的地图；
- 创建地图和写 registry 任一步失败时回滚已创建地图物品；
- remove 先解除 registry，再清除受管理地图，不删除实体或方块；
- marketId 必须存在；
- mode/period 更新后立即安排刷新。

**Step 4: Implement command administration**

将复杂的 Bukkit 操作放在 `MarketDisplayAdministration`，router 只解析和分派。

**Step 5: Add configuration and messages**

`config.yml`：

```yaml
displays:
  enabled: true
  refresh-seconds: 5
  max-map-walls: 128
  max-signs: 256
```

新增命令成功、无目标、错误目标、未知市场、限制达到、刷新排队、解除绑定和失败消息。

**Step 6: Add permission and tab completion**

`plugin.yml` 新增 `quickshop.exchange.admin.display`，默认 `op`；补充 `display` 命令分支候选。

**Step 7: Run tests and verify GREEN**

Run command, administration, display service and listener tests. Expected: PASS.

**Step 8: Commit checkpoint**

Commit only Task 6 files.

### Task 7: 文档、完整验证和可部署 JAR

**Files:**
- Modify: `docs/exchange-operations.md`
- Modify: `outputs/QuickShop-Exchange-管理员使用指南.md`
- Modify: `outputs/QuickShop-Exchange-玩家入门到精通.md` only if players need protection/visibility notes
- Create: `outputs/Addon-Exchange-6.3.0.0-SNAPSHOT-11-market-displays.jar`

**Step 1: Update operations documentation**

记录：

- display 命令和权限；
- `displays.yml` 备份/恢复；
- 1×1、2×1、2×2 布置要求；
- Paper/Folia 所有者线程行为；
- 区块未加载时的延迟刷新语义；
- 删除绑定而不删除展示框/告示牌的安全策略；
- 刷新间隔和容量限制。

**Step 2: Run focused display tests**

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -pl addon/exchange -am "-Dtest=*MarketChart*,*MarketDisplay*,*MarketSign*,AdminCommandRouterTest,ExchangeRuntimeFactoryTest,ExchangeRuntimeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: BUILD SUCCESS.

**Step 3: Run Exchange module verification**

```text
C:\Users\ztrnb\.workbuddy\binaries\maven\versions\apache-maven-3.9.16\bin\mvn.cmd -pl addon/exchange -am verify
```

Expected: BUILD SUCCESS with all unit/integration tests passing.

**Step 4: Check diff hygiene**

```bash
git -C "C:/Users/ztrnb/Documents/QuickShop-Hikari/.worktrees/exchange-safety-review" diff --check
```

Expected: no output and exit code 0.

**Step 5: Inspect packaged JAR**

Verify the JAR contains:

- `com/ghostchu/quickshop/addon/exchange/display/` classes;
- updated `plugin.yml`, `config.yml`, `messages.yml`;
- no test classes or temporary files.

**Step 6: Copy and hash deliverable**

Copy the newly built JAR to:

```text
C:\Users\ztrnb\Documents\QuickShop-Hikari\outputs\Addon-Exchange-6.3.0.0-SNAPSHOT-11-market-displays.jar
```

Calculate SHA-256 and report the exact value.

**Step 7: Final review**

Use `superpowers:requesting-code-review` and address all P0/P1 findings before declaring release-ready.

**Step 8: Record project memory**

Append the implementation, tests, artifact path and SHA-256 to `.workbuddy/memory/2026-07-30.md` without overwriting prior entries.
