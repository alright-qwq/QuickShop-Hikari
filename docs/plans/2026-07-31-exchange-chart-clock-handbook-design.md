# Exchange 专业行情图、GUI 时钟与交易手册设计

## 目标

在不改变撮合、资产、结算和现有 TNML 页面切换语义的前提下，为 QuickShop-Hikari Exchange 增加三项玩家体验增强：

1. 更接近现实交易软件的专业行情地图图表；
2. 所有 Exchange GUI 页面统一显示可配置时区的日期时间；
3. 玩家可领取带防伪标记的交易手册，右键直接打开交易市场首页。

## 固定设计决策

### 专业行情图

- 保留 `KLINE` 与 `LINE` 两种模式以及 `1x1`、`2x1`、`2x2` 三种尺寸。
- 使用中国行情配色：上涨红、下跌绿、平盘灰。
- `1x1` 显示现价、涨跌、主图、最新价线和精简刻度。
- `2x1` 增加市场名、周期、价格轴、时间轴和简化成交量。
- `2x2` 显示完整摘要、主图、最新价标签、价格/时间刻度和成交量柱。
- 网格降低对比度，价格范围增加上下留白，避免走势贴边。
- 单 Candle 在折线模式下显示高亮点和最新价线，不再只有一个难以识别的像素。
- 图表继续使用纯 Java byte 像素缓冲区，不引入 AWT。
- 地图快照合并数据库 Candle 与 `MarketDataService` 当前内存 Candle；相同分钟由内存版本覆盖数据库版本，避免重复成交量。

### GUI 时间

- 所有页面通过公共 `ExchangeMenuChrome.prepare(...)` 在顶部显示独立 `CLOCK` 图标。
- 默认时区为 `Asia/Shanghai`，可在 `config.yml` 设置标准 `ZoneId`。
- 默认格式为 `yyyy-MM-dd HH:mm`。
- 时间只在页面打开、页面切换或页面自身刷新时重新计算，不增加每秒定时任务。
- 无效时区回退服务器默认时区；无效格式回退默认格式，并各记录一次警告。
- 测试通过注入固定 `Clock` 保证确定性。

### 交易手册

- 玩家使用 `/qse book` 自助领取；管理员保留 `/qse admin book give <player>` 补发入口。
- 默认材质为 `KNOWLEDGE_BOOK`，物品名称和 lore 使用本地化消息。
- 使用独立 `NamespacedKey` 和 PDC 字符串版本标记识别合法手册；改名或复制 lore 的普通书无效。
- 手册允许丢弃、放入容器和转交其他玩家。
- 默认防止同一玩家背包内重复领取；背包已满时不掉落到世界。
- 右键仅处理主手事件，取消原版交互，并在玩家 Entity Scheduler 上打开市场首页。
- 使用者必须拥有 `quickshop.exchange.use`，并通过现有 `RolloutPolicy`；功能关闭时不打开 GUI。

## 架构与数据流

### 行情图数据流

```text
Repository candles ─┐
                    ├─ ExchangeMarketDisplayDataSource 合并/去重
Live candles ───────┘
        ↓
MarketDisplaySnapshot
        ↓
MarketChartSeriesBuilder
        ↓
MarketChartLayout + MarketChartRenderer
        ↓
MarketChartImage
        ↓
MarketChartSlices
        ↓
Folia Entity Scheduler 更新展示框地图
```

`MarketDataService` 提供指定市场和时间窗口的不可变内存 Candle 快照。展示数据源使用按 `bucketStart` 排序的 Map 合并持久化和内存数据，同分钟以内存 Candle 覆盖，以反映当前 close/high/low/volume。

### GUI 时间数据流

```text
config.yml
  ↓ 校验 ZoneId / DateTimeFormatter
ExchangeClockDisplay
  ↓
ExchangeMenuService → ExchangeMenu → 页面构造器
  ↓
ExchangeMenuChrome.prepare(...)
  ↓
slot 7 CLOCK 图标（日期时间 + 时区 lore）
```

槽位 7 当前属于顶部边框，时钟会覆盖该槽位；槽位 4 标题、槽位 8 帮助和底部导航保持不变。

### 交易手册数据流

```text
/qse book
  ↓
ExchangeCommandRouter
  ↓
CommandActor.claimHandbook()
  ↓
ExchangeHandbookService.claim(player)
  ↓
PDC 标记物品进入背包

PlayerInteractEvent（主手右键）
  ↓
ExchangeHandbookListener
  ↓ PDC、防开关、权限、rollout
Player Entity Scheduler
  ↓
ExchangeMenuService.open(player, markets)
```

## 配置

```yaml
gui:
  clock:
    enabled: true
    zone-id: "Asia/Shanghai"
    format: "yyyy-MM-dd HH:mm"

handbook:
  enabled: true
  self-claim: true
  prevent-duplicate: true
  material: "KNOWLEDGE_BOOK"

displays:
  chart:
    professional-layout: true
    include-live-candle: true
    show-volume: true
    show-latest-price-line: true
```

第一版代码允许关闭主要增强，但不把每个像素布局细节暴露成配置，避免产生不可测试的组合爆炸。

## 异常处理

- 无行情数据：显示市场名、周期和无数据占位，不伪造走势。
- 单 Candle 或全同价：使用安全价格 padding，避免除零。
- 极大或高精度价格：价格标签使用自适应紧凑格式并限制字符宽度。
- 图表区域不足：按“次要刻度、成交量文字、摘要”的顺序降级，主图和最新价始终保留。
- 实时 Candle 读取失败：展示刷新失败，不修改上一张有效地图；撮合和结算不受影响。
- 手册功能关闭、无权限或 rollout 拒绝：发送明确消息，不打开菜单。
- 背包已满：不向地面掉落，玩家腾出空间后可重新领取。
- 插件关闭阶段调度器拒绝任务：不回退到跨线程打开背包或 GUI。

## 测试与验收

### 自动化测试

- 图表布局不越界，三种尺寸均保留主图区。
- K 线/折线、最新价线、成交量、单点高亮和中国涨跌色。
- 持久化 Candle 与当前内存 Candle 合并、覆盖与去重。
- 固定 Clock 下的时间格式、时区、关闭开关和无效配置回退。
- 公共 Chrome 不覆盖标题、帮助和底部导航。
- `/qse book` 成功、重复、背包满、功能关闭、权限和 rollout。
- PDC 防伪、主手右键单次打开、副手/左键/普通书不触发。
- 管理员补发和 Folia 玩家所有者调度。

### 实服验收

1. 创建 1x1、2x1、2x2 图墙并切换 KLINE/LINE。
2. 跨分钟制造不同价格成交，确认当前分钟无需等待落盘即可出图。
3. 检查单点、上涨、下跌、平盘、成交量和最新价线。
4. 打开市场、资产、挂单、历史和确认页，确认时钟位置与格式一致。
5. `/qse book` 领取后用主手右键打开市场；转交给另一名有权限玩家后仍可使用。
6. 验证无权限、未在 rollout、背包满和副手交互均安全拒绝。
