# Exchange Order Book 开发交接

## 授权与用户目标

用户明确表示已获得原仓库作者同意使用 AI 继续开发。本次功能的业务目标是：在大型服务器中，以接近现实证券市场的中央订单簿实现供需定价，避免贵重或廉价物品在大量交易时只依赖固定价格而持续贬值。

目标不是随机调价公式，而是基于真实成交形成价格发现，并逐步加入价格时间优先、Maker/Taker 手续费、资产预留、市价保护、自成交保护、价格笼子、熔断、幂等和串行处理。

正式设计与分阶段计划：

- `docs/superpowers/specs/2026-07-26-exchange-order-book-design.md`
- `docs/superpowers/plans/2026-07-26-exchange-01-matching-engine.md`
- `docs/superpowers/plans/2026-07-26-exchange-02-persistence-ledger.md`
- `docs/superpowers/plans/2026-07-26-exchange-03-custody-transfers.md`
- `docs/superpowers/plans/2026-07-26-exchange-04-addon-ui-operations.md`

## Git 状态

- 工作分支：`codex/exchange-order-book`
- 远程：`origin`，即 `alright-qwq/QuickShop-Hikari`
- 本交接文档之前的代码 HEAD：`e85683acf018437a37fe9fe8fdd8256b1e7a9e18`
- 当前阶段尚未创建 PR。

Task 5 的主要提交：

- `bec534d4f` — `feat(exchange): add fees reservations and market IOC`
- `e85683acf` — `fix(exchange): reserve executable market buy depth`

## 已完成且审查通过

- Task 1：Exchange Addon 与独立测试运行时。
- Task 2：市场、订单、成交等领域类型及不变量。
- Task 3：严格价格优先、同价 FIFO 的订单簿。
- Task 4：限价撮合、Maker 成交价、部分成交及原子发布。

必须继续保持的 Task 4 行为：

- OrderBook 首次使用后永久绑定市场，即使清空也不能切换市场。
- 修改订单簿前先完成全部校验、时间/ID 获取、费用计算和对象构造。
- 重复订单 ID、跨市场和非 `OPEN` 入参必须在订单簿修改前拒绝。
- 对整条可成交链预检自成交；如果剩余量将碰到自己的订单，整笔新订单原子拒绝，不产生任何成交。
- 部分成交替换不能改变账户、请求 ID、原始数量、优先序列等不可变字段，也不能增加剩余量。

## Task 5 当前状态

已实现：

- 手续费按货币精度向上舍入。
- Maker/Taker 费率按成交角色分别计算。
- 限价买单、市价买单和卖单的资产预留类型。
- 市价单 IOC 余量取消，不进入订单簿。
- 无可成交对手盘的市价单在修改订单簿前拒绝。
- 市价买单按可成交卖盘深度和滑点边界计算预留，未成交 IOC 余量不冻结资金。
- 自成交整链预检与 staged publication 行为得到保留。

在代码 HEAD `e85683acf` 上，Java 21 下运行 `mvn -pl addon/exchange test` 的结果为 32 个测试通过、0 失败、0 错误。

Task 5 **尚未审查通过，也不要在进度账本中标记完成**，因为还有下面的 Important 问题。

## 首要待办：修复逐笔手续费舍入导致的预留不足

当前 `ReservationCalculator` 先汇总所有可执行成交额，再对 Taker 手续费向上舍入一次；`MatchingEngine` 则对每笔 Trade 分别向上舍入手续费。多个小额成交时，预留资金可能小于实际扣费。

必须先按 TDD 添加回归测试：

- 两个卖方 Maker 订单，价格均为 `1.01`，数量均为 `1`。
- 市价买单数量为 `2`，可成交范围覆盖两个订单。
- Taker 费率为 `0.002`，货币精度为 `2`。
- 每笔成交的 Taker 费均为 `0.01`。
- 总成交额为 `2.02`，总 Taker 费必须为 `0.02`。
- `Reservation.frozenCurrency()` 必须为 `2.04`。
- 当前实现会按总成交额只计算 `0.01` 手续费，因此会得到错误的 `2.03`；新测试应先证明这一红灯。

最小修复方向：在遍历可执行深度时，对每个 staged fill 的名义金额分别调用 `FeeCalculator.fee(...)`，再累加已经舍入的逐笔 Taker 费。不要改变撮合引擎当前按 Trade 逐笔收费的语义。

修复后运行：

```powershell
mvn -pl addon/exchange -Dtest=FeesMarketAndSelfTradeTest,LimitMatchingTest test
mvn -pl addon/exchange test
```

建议提交信息：

```text
fix(exchange): reserve per-fill market fees
```

随后必须重新生成 Task 5 的完整差异审查包，派独立 reviewer 同时给出规格符合性和代码质量结论。只有 Critical/Important 全部关闭后，才能把 Task 5 标记完成。

## Task 5 完成后的顺序

继续执行 `2026-07-26-exchange-01-matching-engine.md`：

1. Task 6：参考价、价格笼子和两级熔断。
2. Task 7：每市场串行执行和 `requestId` 幂等。
3. Task 8：资产性质测试和性能回归门槛。

仍采用 Subagent-Driven Development：每个任务使用新的实现者、严格 TDD、提交、独立任务审查、修复复审；全部任务完成后再进行整分支审查。

## 构建环境与限制

- 需要 Java 21。
- 当前机器使用 Maven 3.9.11；其他机器只要兼容即可。
- 全仓库构建可能因 CodeMC 下载 `de.tr7zw:item-nbt-api-plugin:2.15.0` 连接重置而失败。
- 当前机器为了运行纯 Exchange 核心测试，在本地 Maven 缓存安装了空的 `com.ghostchu:quickshop-bukkit:6.3.0.0-SNAPSHOT-11` 占位 artifact。该 artifact 不是交付物；新机器必须解析真实依赖或临时重建本地测试占位，接入真实 QuickShop API 前必须移除占位。

## 不属于该分支的本地用户修改

原始 checkout 中以下修改属于用户，未纳入并且不应被此功能分支覆盖：

```text
 D .github/copilot-instructions.md
 D AGENTS.md
 D EULA.md
 M README.md
```

新机器从远程分支检出时不会包含这些未提交修改。
