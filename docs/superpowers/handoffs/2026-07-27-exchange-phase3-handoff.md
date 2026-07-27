# Exchange Phase 3 交接（2026-07-27）

## 授权与目标

仓库作者已授权用户使用 AI 开发和发布。该授权覆盖根目录 `AGENTS.md` 和
`EULA.md` 中禁止 AI 的条款；其他工程和安全约束仍然有效。

整体目标未变：完成订单簿交易所的 Phase 2--4。权威 Phase 3 计划是：

- `docs/superpowers/plans/2026-07-26-exchange-03-custody-transfers.md`

## Git 状态

- 分支：`codex/exchange-order-book`
- 远程：`origin` 指向 `alright-qwq/QuickShop-Hikari`
- 本交接提交前的远程基准：`f8d50c539`
- 当前本地分支包含 5 个尚未推送的 Phase 3 提交和本交接 WIP 提交。
- 本次未创建 PR，未合并到 `hikari`。

新电脑恢复：

```bash
git fetch origin
git switch --track origin/codex/exchange-order-book
# 若远程尚未包含本交接提交，则先从当前电脑 push，或把该提交迁移到新电脑。
```

## 已完成并已提交

### Phase 2

Phase 2 Task 8 已完成，远端基准为 `f8d50c539`。本机无 Docker，真实 MySQL
的 7 个用例尚需在 Docker 主机补跑；非 Docker 全量记录为 140 passed、7 MySQL
skipped、0 failures。

### Phase 3 Task 1--4

- `4f6f6b794` `feat(exchange): persist transfer state machine`
  - 转账模型、requestId 幂等创建及 CAS 状态迁移。
- `4d4f472d4` `fix(exchange): reconcile signed custody balances`
  - 托管核对固定为 `custody + liabilities == 0`。
- `003959fc3` `feat(exchange): add safe money deposits`
  - 外部扣款成功后才记内部资产；UNKNOWN 进入 `REVIEW_REQUIRED`。
- `d6f16a25e` `feat(exchange): add safe money withdrawals`
  - 唯一创建后才冻结；成功/明确失败的余额、journal 和终态 CAS 同一 JDBC 事务。
- `20aae332f` `feat(exchange): adapt quickshop economy provider`
  - QuickShop `EconomyProvider` 适配：`true -> SUCCESS`、`false -> FAILURE`、异常
    `-> UNKNOWN`；`default` currency 映射为 provider 的 `null`；不做 `BigDecimal`
    到 `double` 的转换。
  - Task 4 独立审查通过，无 Critical/Important/Minor。

已验证：

```powershell
& 'C:\Users\ztrnb\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' `
  -pl addon/exchange `
  '-Dtest=QuickShopEconomyGatewayTest,MoneyDepositTest,MoneyWithdrawalTest' `
  '-Dapi.version=1.44' test
```

结果为 8 tests、0 failures、0 errors、0 skipped。Maven 会产生仓库原有模型、SLF4J
provider 和 Java native-access 警告。

## 当前 WIP：Phase 3 Task 5，不能视为完成

本交接提交会保留下列未完成文件，方便继续而不丢失红灯测试和实现草稿：

- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/FingerprintMode.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprint.java`
- `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintService.java`
- `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/platform/ItemFingerprintServiceTest.java`
- `addon/exchange/pom.xml` 的 `quickshop-platform-interface` provided 依赖。

已实现草稿遵循计划：数量规范为 1、只移除 exchange transfer marker、严格模式使用
`encodeStack` 的 SHA-256、普通材料同时要求双向 `ItemMatcher` 匹配和编码完全相等。
直接声明 `quickshop-platform-interface` 是必要的，因为本机已安装的 QuickShop shade
consumer POM 没有传递 `QuickShop.platform()` 的公开返回类型。

### 阻断

以下聚焦测试能编译，但不能执行：

```powershell
& 'C:\Users\ztrnb\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' `
  -pl addon/exchange '-Dtest=ItemFingerprintServiceTest' '-Dapi.version=1.44' test
```

Paper API 在普通 JVM 下构造 `new ItemStack(Material.DIAMOND)` 时抛出：

```text
IllegalStateException: No RegistryAccess implementation found
```

因此 Task 5 **没有完成、没有经过独立审查、没有跑全量测试**。当前测试里的
`TestQuickShop` 使用 `Unsafe` 仅是临时 fixture，也不应作为最终方案保留。

### 下一位 AI 的起点

1. 为 `addon/exchange` 采用项目兼容的 Paper 测试服务器 bootstrap（优先验证可用的
   MockBukkit/Paper 对应版本），不要绕过 `ItemStack` 的 RegistryAccess 要求。
2. 移除 `ItemFingerprintServiceTest` 的 `Unsafe` fixture，使用测试服务器和正常的
   QuickShop/平台边界 fake 或可测试端口。
3. 重新执行 Task 5 的 TDD 聚焦测试，确保 amount 与 transfer marker 不影响严格指纹，
   其他元数据会改变严格指纹，普通材料拒绝 metadata。
4. 通过聚焦和全量测试后，提交 Task 5 并进行独立规范/质量审查；随后按计划执行
   Task 6（Folia 背包网关）、Task 7（物品存取）和 Task 8（恢复）。

## 仍需完成的验证

- Phase 3 Task 5--8 完成后，执行计划中的验收：

```bash
mvn -pl addon/exchange -Dtest='*Transfer*Test,*GatewayTest,*Fingerprint*Test' test
mvn -pl addon/exchange verify
```

- 在 Docker 可用主机补跑 Phase 2 的 7 个 MySQL 用例。
- Phase 3 全部完成后做整分支独立代码审查，再进入 Phase 4。

## 本地辅助记录

`.superpowers/sdd/progress.md` 记录任务进度；该文件已随本交接 WIP 提交。Task 5
的详细执行记录另在 `.superpowers/sdd/task-5-report.md`，不应替代本文件的可传递
交接说明。
