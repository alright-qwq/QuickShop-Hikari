# 交接文档:Exchange 放置修复 + 图表绘制修复已完成(2026-08-03)

## 1. 当前状态

- 分支:`fix/exchange-safety`,已推送远端,最新提交 `5968ff863`。
- 工作树:本文件外无未提交改动;`outputs/` 为本地产物目录(不入库)。
- 全量测试:648 tests, 0 failures(`addon/exchange`)。
- 兼容基线:QuickShop-Hikari 6.2.0.11 / Paper 26.1.2。

## 2. 本轮已完成:图放不下来的修复(用户首要问题)

用户反馈「打出放置命令后显示出现问题,已回退」。根因是放置链路里异常被吞:
命令只回通用 `display-operation-failed`,玩家和服务器日志都看不到真实原因,
一旦某一步失败(最可疑的是 `Bukkit.createMap` 返回 null)就静默回退。

### 修复(提交 1625d1f7d)
1. `MarketDisplayAdministration` 新增 failureReporter:所有显示操作(创建/移除/绑定/更新)
   失败时,根因以 SEVERE 级别写入插件日志(`Exchange display <context> failed`),玩家仍收到
   通用消息但服务器日志现在能看到完整堆栈。
2. `BukkitDisplayTargets.createMaps`: `createMap` 返回 null 时抛出明确
   `IllegalStateException("the server did not provide a new map view for <world>")`,
   不再 NPE;失败会回滚已创建的框并记录根因。
3. 逐框创建改走可注入的 scheduler(生产行为不变,仍 1 tick),使回滚路径可被单元测试覆盖。

### 需要用户侧做的(仍然有效)
1. 用 `outputs/exchange-6.3.0.0-SNAPSHOT-11-reload-with-market-add-remove.jar`
   替换服务器 `plugins` 下的旧 exchange JAR(本批次已重新构建并验证 shade)。
2. `markets.yml` 中 `minecraft_netherite_ingot.discovery-quantity: 8` 不合法(需 ≥10,建议 16),
   否则重启报 `invalid structural market rules`。
3. 重启后重试 `/qs exchange admin display map create ...`;若再失败,
   把插件日志里的 `Exchange display map create failed` 堆栈发回(现在一定会有根因)。

## 3. 图表绘制修复(已完成,提交 5deb59dc5 / 5b9190eef / 5fef263b8)

1. **时间轴本地化+日期**:`MarketChartRenderer` 使用服务器本地时区(不再写死 UTC),
   跨天图表标签带 `MM-dd` 前缀;1 小时等日内图保持 `HH:mm`。
2. **超预算降采样**:`AdaptiveChartIntervalSelector.downsample` 在活跃 run 数超过预算时
   不再原样返回,改为每个 run 一个代表点,保留稀疏时间线与缺口标记。
3. **PixelFont 补全**:新增 C/J/P/Q/U/V/X/Z 字形(全 26 字母齐备),QUARTZ/COAL 不再缺字。
4. **价格刻度对齐网格**:0/25/50/75/100% 每个网格线都有价格标签。
5. **量柱下跌色**:`VOLUME_FALL` 从 29(与 BORDER 相同)改为 13(深绿),下跌量柱可见。
6. **缓存可命中**:`MarketDisplaySnapshot.fingerprint` 去掉纳秒级 `asOf`,
   价格/盘口/蜡烛不变时刷新不再重绘。
7. **可信价注入覆盖**:时间戳冲突时用当前可信价覆盖旧点(`putIfAbsent` → `put`)。
8. **LINE 模式跨缺口不连线**:缺口两侧不画连线,保留缺口标记。
9. **可信价存在时原始价整线**:`drawRawLatestMarker` 由 4px 刻度改为整条虚线。
10. **2x1(WIDE)布局加时间轴**:压缩 volume 区,新增紧凑时间轴。

### 标题(已改进,仍有已知限制)
- **中文标题回退为市场 ID**(提交 984de9273):PixelFont 是 3×5 位图字体,中文无法渲染,
  但标题现在回退为 ASCII 的市场 ID(如 `MINECRAF...`),不再是无信息量的 `MARKET`。
- 真正的 CJK 渲染需要引入位图字体或更大的标题方案,属独立较大改动,建议与用户确认是否必要。

## 4. 下一步建议

1. 用户换新 JAR + 改 markets.yml + 重启,验证放置成功;若有失败日志,按第 2 节收集。
2. 真机确认地图渲染内容(标题仍为 MARKET,时间/价格已本地化)。
3. 标题已回退为市场 ID;若用户要求显示中文名,再评估 CJK 字体方案。
4. 后续大功能(管理员热操作 GUI 等)未展开。

## 5. 环境注意事项

- 本机 GitHub 直连不通,git 命令必须带:
  `-c http.proxy=http://127.0.0.1:7897 -c credential.helper="!gh auth git-credential"`
  (gh 已登录 alright-qwq,有 repo 权限)。
- 禁止 `git reset --hard` / `git clean` / 大范围 restore;`outputs/` 为本地产物,保留即可。
- 构建正确 JAR:`mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 -DskipTests package`,
  取 `addon/exchange/target/Addon-Exchange-6.3.0.0-SNAPSHOT-11.jar`(shaded 主构件,774KB),
  复制到 `outputs/` 下正确的文件名;`target/exchange-*.jar` 是旧未 shade 构件,不要发用户。
- 全量测试:`mvn -o -f pom.xml -pl addon/exchange -am -Dapi.version=1.44 test`(648 tests)。
- 最新 JAR 已重建:`outputs/exchange-6.3.0.0-SNAPSHOT-11-reload-with-market-add-remove.jar`。
