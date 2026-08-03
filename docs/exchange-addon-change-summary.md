# QuickShop-Hikari Exchange 插件变更总览

> 更新时间：2026-07-31
> 目标运行环境：QuickShop-Hikari 6.2.0.11、Paper/Folia、Java 21
> 状态说明：本文严格区分“已实现并验证”与“已确认设计、尚未实现”。

## 1. 项目目标

Exchange addon 为 QuickShop-Hikari 增加中心化交易市场，使玩家能够充值物品或货币、提交限价单或受保护市价单、查看挂单和历史，并通过托管、冻结、结算、幂等与审计保证资产安全。

本轮工作主要解决 QuickShop-Hikari 6.2.0.11 的 GUI 二进制兼容和页面交互问题，并进一步加入行情地图墙、行情告示牌、专业图表、GUI 时钟与交易手册。后续又针对 Minecraft 低流动性经济重新评估证券式风控，形成主动式防操纵方案。

---

## 2. 已实现：QuickShop 6.2 / 旧 TNML GUI 兼容

### 2.1 二进制兼容

- 移除旧 TNML 不存在的 `PlayerInstancePage.setLockEmptySlots(boolean)` 直接调用，解决 `NoSuchMethodError`。
- 增加 `ItemStackCompat`：优先调用新版 `customName(Component)`，在 QuickShop 6.2 环境捕获 `NoSuchMethodError` 后回退至 `display(Component)`。
- 兼容日志中的回退提示仅表示运行时选择了旧 API，并非点击失败或 GUI 崩溃。

### 2.2 Loading 与异步刷新

- 旧 TNML 不会在异步新增图标后自动刷新，页面因此会永久停留在 Loading 时钟。
- 数据加载调整为在页面打开回调完成前构造可显示内容，避免页面生成后再异步修改但不刷新。
- 补强菜单输入保护，阻止数字键、Shift 点击等方式把物品放入 Exchange GUI。

### 2.3 玩家实例图标与导航栏

- 旧 TNML 在玩家页面实例已存在时，`addIcon(UUID, Icon)` 可能覆盖玩家实例图标集合。
- 新增 `ExchangePageIcons`：若实例不存在则正常添加；若实例已存在则直接更新该玩家的 icon map。
- 修复市场、资产、挂单、历史等底部导航缺失。

### 2.4 点击入口与页面切换

- 新增 `ExchangePlayerPage`，覆盖 TNML 实际使用的 `onClick(MenuClickHandler)`，再按玩家 UUID 转发到父类逻辑。
- 新增 `ExchangeMenuNavigator`，切换页面前设置 `ViewerStatus/CoreStatus.SWITCHING`。
- 避免旧页面关闭事件被误判为玩家退出菜单，从而导致“控制台显示 Action 已执行，但目标 GUI 没打开”。
- 市场详情、资产、挂单、历史、确认页和分页由此形成完整可点击闭环。

---

## 3. 已实现：交易核心与玩家操作闭环

Exchange 当前保留并强化以下适合 Minecraft 的核心能力：

- 中央限价订单簿；
- `LIMIT` 与受保护 `MARKET` 订单；
- `GTC` 与 `IOC`；
- Maker/Taker 手续费；
- 成交价取先挂出的 Maker 价格；
- 同一账户自成交保护；
- 充值、提现、冻结资产、撤单和结算；
- 请求 ID 幂等；
- SQLite/MySQL 持久化；
- 账本、审计、对账和恢复；
- Paper/Folia 所有者线程调度。

当前市场规则包含：

- 绝对价格范围：`min-price` / `max-price`；
- 价格精度：`price-scale`；
- 最小价格变动：`tick-size`；
- 动态价格笼子：默认参考价上下 20%；
- 市价单默认滑点 5%、最大滑点 20%；
- 一级/二级熔断：默认 10% 暂停 120 秒、20% 暂停 600 秒。

风控拒绝目前可能表现为 `IllegalStateException`，常见原因包括 `PRICE_OUTSIDE_CAGE`、`SLIPPAGE_TOO_HIGH` 和 `MARKET_NOT_OPEN`。`HALTED` 表示市场因价格波动进入临时熔断，并非插件崩溃；到期后维护任务自动恢复为 `OPEN`。

---

## 4. 已实现：行情地图墙与行情告示牌

### 4.1 地图墙

- 原版地图放入物品展示框，组成可悬挂行情图墙。
- 支持 `KLINE` 与 `LINE` 两种模式，每块图墙可独立切换。
- 支持 `1x1`、`2x1`、`2x2` 三种尺寸。
- 支持 `1h`、`6h`、`24h`、`7d` 周期。
- 使用中国行情配色：上涨红、下跌绿、平盘灰。
- 使用纯 Java `byte[]` 像素渲染，不依赖 AWT，适合无头服务器。
- 图像按从左到右、从上到下切成 128×128 地图片段。

### 4.2 告示牌

告示牌可绑定市场，并显示：

1. 市场名称；
2. 当前价格；
3. 24 小时涨跌；
4. 最佳买价/卖价，或当前市场状态。

`bestBid` / `bestAsk` 来自当前订单簿。完全成交后没有剩余挂单时显示 `-- / --` 属于正常。24 小时涨跌按窗口第一根 Candle 的 open 与最后一根 Candle 的 close 计算；只有一笔成交时涨跌为 0%。

### 4.3 数据、持久化与 Folia

- 成交聚合为 UTC 分钟 OHLCV Candle。
- 展示数据源合并数据库 Candle 与当前内存 Candle；同一分钟由 live Candle 覆盖 persisted Candle，避免重复成交量。
- 修复非整分钟查询窗口排除当前部分分钟的问题。
- 使用异步查询与像素计算。
- 地图更新通过 Folia Entity Scheduler，告示牌更新通过 Region Scheduler。
- 不强制加载区块。
- 绑定持久化到 `displays.yml`，写入采用临时文件和原子替换策略。

### 4.4 实服修复

- 修正 `ItemFrame.getAttachedFace()` 方向理解，解决 `2x1`、`2x2` 地图墙左右反转。
- 告示牌射线检测不再忽略可穿过方块，解决无法注视锁定告示牌。
- 除交互与 `HangingBreakEvent` 外，在最高优先级取消受管理展示框的 `EntityDamageEvent`，解决第一次攻击就弹出地图的问题。
- 普通玩家不能旋转、取下、覆盖或破坏受管理展示。

### 4.5 管理命令

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

权限：`quickshop.exchange.admin.display`，默认仅 OP。

---

## 5. 已实现：专业行情图第一版

专业行情布局在原 K 线/折线基础上增加：

- 顶部行情摘要；
- 柔和网格；
- K 线或折线主图区；
- 最新价虚线和标签；
- 右侧价格轴；
- 底部时间轴；
- 成交量柱；
- 折线单点十字高亮；
- 纯 Java 3×5 ASCII 像素字体；
- 极大/极小价格科学计数；
- 非 ASCII 市场名安全降级；
- `1x1`、`2x1`、`2x2` 的 `COMPACT`、`WIDE`、`FULL` 分级布局。

配置：

```yaml
displays:
  chart:
    professional-layout: true
    include-live-candle: true
    show-volume: true
    show-latest-price-line: true
```

远端最新实现已在第一版专业布局之上加入稀疏 Candle、自适应 `5m / 15m / 1h / 6h / 24h` 周期、真实空档标记、可信参考价线与流动性提示。图表不为无成交时间伪造 Candle，并会根据地图尺寸和有效数据量选择可读周期。

---

## 6. 已实现：GUI 可配置时钟

所有 Exchange GUI 通过公共 Chrome 在顶部槽位 7 显示时间。

```yaml
gui:
  clock:
    enabled: true
    zone-id: Asia/Shanghai
    format: yyyy-MM-dd HH:mm
```

特性：

- 默认中国标准时间；
- 支持标准 `ZoneId` 和自定义 `DateTimeFormatter`；
- 仅在页面打开、切换或刷新时计算，不创建每秒 scheduler；
- 无效时区或格式在启动时警告并安全回退；
- 可注入 `Clock`，便于确定性测试；
- 不覆盖标题、帮助图标和底部导航。

---

## 7. 已实现：交易手册快捷入口

### 7.1 玩家与管理员命令

```text
/qse book
/qse admin book give <player>
```

管理员权限：`quickshop.exchange.admin.handbook`。

### 7.2 防伪和使用流程

- 默认材质 `KNOWLEDGE_BOOK`；
- 使用 `NamespacedKey` + PDC 字符串版本 `v1` 防伪；
- 普通改名或仿制 lore 的物品不会被识别；
- 手册允许丢弃、存入容器和转交；
- 可配置防止同一背包重复领取；
- 背包已满时拒绝领取，不向世界掉落；
- 主手右键时取消原版交互；
- 使用者仍需拥有 `quickshop.exchange.use` 并通过 Rollout；
- 在玩家 Entity Scheduler 上打开市场首页。

```yaml
handbook:
  enabled: true
  self-claim: true
  prevent-duplicate: true
  material: KNOWLEDGE_BOOK
```

### 7.3 管理员异步补发可靠性

新增独立 `HandbookAdminCommands`，覆盖以下终态：

- 目标玩家不存在；
- Entity Scheduler retired；
- 调度抛出异常；
- `give(...)` 抛出异常；
- 发放返回非成功结果；
- 管理员在异步完成前离线。

调度前捕获目标玩家名称，背包修改只在目标玩家 owner 线程执行；回执通过管理员 actor 的 completion 调度，避免跨 owner 读取 Bukkit `Player`。

---

## 8. 已实现：消息文件升级兼容

旧服务器的磁盘 `messages.yml` 不会因为插件升级自动获得新增键。消息加载已改为：

```text
有效消息 = JAR 内置默认消息 + 磁盘自定义覆盖
```

效果：

- 保留管理员已有翻译和自定义文案；
- 自动从 JAR 默认资源补齐时钟、手册和新 GUI 所需消息键；
- 磁盘中已有键继续优先；
- 覆盖默认值、旧文件缺键及多语言回退均有测试。

---

## 9. 已完成验证与发布产物

定向验证：

```text
Tests run: 109
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

完整 Maven Reactor 验证：

```text
Tests run: 509
Failures: 0
Errors: 0
Skipped: 0
Reactor: 7/7 SUCCESS
BUILD SUCCESS
```

最终发布 JAR：

```text
outputs/Addon-Exchange-6.3.0.0-SNAPSHOT-11-professional-chart-clock-handbook.jar
```

- 大小：642997 bytes
- SHA-256：`47AFFBDC8314B6D279C40A2A6BC89828F104CE536A394B5A3D244E50FA533858`
- 已确认包含专业图表、时钟、手册、管理员补发、配置、消息和插件描述类/资源；不包含测试类。

早期阶段产物还包括：

- `Addon-Exchange-6.3.0.0-SNAPSHOT-11-tnml-page-switch-fixed.jar`
- `Addon-Exchange-6.3.0.0-SNAPSHOT-11-market-displays-direction-sign-fixed.jar`
- `Addon-Exchange-6.3.0.0-SNAPSHOT-11-market-displays-protected.jar`

最终部署应优先使用最新的 professional-chart-clock-handbook 版本，而不是叠加安装早期 JAR。

---

## 10. 已实现：Minecraft 自适应可信市场机制

### 10.1 主动式可信价格

系统不再把每一笔真实成交等量视为可信参考价，而是执行：

```text
真实成交
  → 正常结算并写入真实 OHLCV
  → 计算请求影响
  → 应用单笔、市场、账户和交易对滚动预算
  → 仅按接受影响推动可信参考价
```

已实现并持久化：

- `TrustedPriceState` 可信市场状态；
- `TradeInfluence` 每笔请求影响、接受影响和限制原因；
- 单笔影响上限；
- 市场、账户和 canonical 交易对窗口预算；
- 指导价锚定区间；
- 无成交回稳；
- Schema V5 数据表和恢复链路。

超出预算的合法成交仍正常结算、进入历史和真实 K 线，只降低其对可信参考价的推动能力。

### 10.2 自适应流动性

`LiquidityClassifier` 使用独立账户、交易对、有效成交、时间分散度、账户集中度和交易对集中度，将市场分为 `LOW / GROWING / STABLE`。等级只调整参考价吸收速度与预算，不决定玩家能否交易，并防止两个账户单纯刷量把市场伪装成稳定市场。

### 10.3 渐进式低干扰行为风险

行为响应分为：

```text
NORMAL → OBSERVE → ALERT → PAIR_COOLDOWN
```

只有重复交易、高交易对集中度、持续活动和明显同向价格压力等多项证据共同成立时才升级。普通和少量重复交易不拒单；`ALERT` 只写后台审计；`PAIR_COOLDOWN` 仅跳过目标交易对，撮合器继续扫描其他 Maker，不暂停整个市场。

默认 `LOW` 市场中，可信引擎将单笔方向影响限制在约 0.5%，冷却又要求约 20% 累计同向压力，因此通常需要约 40 笔持续、高集中、显著同向冲击成交才会执行 5 分钟交易对冷却。该门槛符合“真实玩家基本无感”的约定。

冷却区间采用半开语义：

```text
[最后一笔可疑成交时间, cooldownUntil)
```

在精确截止时刻立即恢复，不因后续评估自我续期。冷却直接根据持久化 `TradeInfluence` 重算，隔离服务重启后仍能恢复，并在到期后自动允许该交易对再次成交。

### 10.4 告警升级去重

审计只记录风险动作升级：

```text
NORMAL / OBSERVE → ALERT
  → 写 1 条 PAIR_BEHAVIOR_ALERT

持续 ALERT
  → 不重复写

ALERT → PAIR_COOLDOWN
  → 写 1 条 PAIR_BEHAVIOR_COOLDOWN

持续 PAIR_COOLDOWN
  → 不重复写
```

去重依据是加入当前成交前后的完整持久化影响序列，而非 JVM 内存标记，因此不会因进程重启重新刷同级告警；同时仍能在单个订单跨多个 Maker 时捕捉首次风险升级。

### 10.5 稀疏自适应行情图

已实现：

- `5m / 15m / 1h / 6h / 24h` 自适应周期；
- 按地图尺寸和有效 Candle 数选择最细可读周期；
- 不为无成交时间伪造 Candle；
- 保留并标记真实空档；
- 真实 OHLCV K 线叠加可信参考价线；
- 单 Candle、平价、极端价格和长时间空窗布局；
- `1x1`、`2x1`、`2x2` 分级信息密度。

---

## 11. 已确认需求、尚未实现：管理员热更新与 GUI

管理员希望无需关服即可调整市场参数，并通过 GUI 完成主要操作。计划页面：

```text
管理中心
└─ 市场管理
   ├─ 选择市场
   ├─ 状态控制
   ├─ 价格与数量
   ├─ 风控与熔断
   ├─ 手续费
   ├─ 图表与展示
   ├─ 查看挂单
   └─ 审计记录
```

计划交互：

```text
点击参数
→ 聊天输入新值
→ 展示旧值、新值和影响预览
→ 输入管理员原因
→ 危险操作二次确认
→ 异步持久化
→ 运行时原子切换
→ 结果页与审计记录
```

安全边界：

- 指导价、价格笼子、熔断阈值等可版本化热更新；
- `min-price` / `max-price` 修改前要评估现有挂单；
- `tick-size`、`price-scale`、交易物品和结算货币属于结构性高风险参数；
- 高风险修改必须先暂停市场，并检查挂单、冻结资产和待处理转账；
- 不提供直接伪造“最新成交价”的普通按钮；应重设稳定参考价/指导价，并完整审计；
- 持久化成功后才能向运行时暴露新版本，失败时保持旧配置。

---

## 12. 主要代码边界

关键实现集中在：

- `ui/ExchangePlayerPage.java`：旧 TNML 点击入口；
- `ui/ExchangePageIcons.java`：玩家实例图标安全更新；
- `ui/ExchangeMenuNavigator.java`：SWITCHING 页面切换协议；
- `ui/ItemStackCompat.java`：QuickShop 6.2/6.3 物品名称兼容；
- `display/`：地图墙、告示牌、图表模型、渲染、缓存、持久化和保护；
- `marketdata/MarketDataService.java`：成交行情和 live Candle；
- `marketdata/CandleAggregator.java`：分钟 OHLCV；
- `ui/ExchangeClockDisplay.java`：可配置时钟；
- `platform/ExchangeHandbookService.java`：PDC 手册创建、验证和领取；
- `platform/ExchangeHandbookListener.java`：主手右键打开市场；
- `platform/HandbookAdminCommands.java`：管理员异步补发；
- `platform/AddonMessageService.java`：默认消息和磁盘覆盖合并；
- `core/risk/ReferencePriceTracker.java`：当前参考价机制，下一阶段重点升级；
- `service/PersistentOrderService.java`：订单持久化、风控、撮合与结算入口；
- `operations/AdminExchangeService.java`：管理员事务、幂等和审计边界；
- `ui/AdminPage.java`：当前只读管理员入口，后续改造成可操作 GUI。

---

## 13. 部署与测试提示

1. 安装前备份 Exchange 数据库、`config.yml`、`markets.yml` 和 `displays.yml`。
2. 不要同时部署多个 Exchange JAR；移除旧版本，只保留最终 JAR。
3. 先在测试服启用 Rollout 白名单。
4. 使用两个不同玩家测试撮合；同一账户自成交会被拒绝。
5. 若要看到涨跌变化，需要至少两个不同成交价格点；数量为 2 的单笔成交仍只有一个价格点。
6. 完全成交后最佳买卖价显示 `-- / --` 是正常现象。
7. Paper 与 Folia 均应验收 GUI、充值提现、成交、撤单、地图刷新、手册右键和插件关闭流程。
8. 双账户串通的真实成交不会被隐藏或回滚，但其可信价格影响受滚动预算约束；只有持续、高集中且同向冲击明显时才升级为交易对级短时冷却。

---

## 14. 结论

本轮已完成 Exchange 在 QuickShop-Hikari 6.2.0.11 上的 GUI 兼容修复和完整交易交互闭环，并交付行情地图墙、行情告示牌、专业与稀疏自适应图表、GUI 时钟、PDC 交易手册、管理员可靠补发及消息升级兼容。

面向 Minecraft 低流动性和少数玩家结构的主动式价格移动预算、单笔影响上限、账户/交易对贡献上限、可信参考价、真实 K 线叠加参考线、渐进式行为风险、交易对短时冷却、告警升级去重和重启恢复均已落地。下一阶段重点是将行为策略配置化，并实现带影响预览、原因输入、二次确认和审计的管理员热更新 GUI。
