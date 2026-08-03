# 交接文档:Exchange 启动崩溃已修复 + 图表"放不下来"待定位(2026-08-03)

## 1. 当前状态

- 工作树:`.worktrees/exchange-safety-review`,分支 `fix/exchange-safety`,工作树干净,已与 `origin/fix/exchange-safety` 同步。
- 最新提交:`070acee5c fix(exchange): ship shaded jar as the main addon artifact`(已推送)。
- 全量测试:635 tests, 0 failures。
- 兼容基线:QuickShop-Hikari 6.2.0.11 / Paper 26.1.2。

## 2. 已完成:启动崩溃修复(NoClassDefFoundError: net/tnemc/menu/core/Menu)

### 根因
部署的 `exchange-6.3.0.0-SNAPSHOT-11-reload-with-market-add-remove.jar` 是未 shade 的构件,字节码引用原始包 `net.tnemc.menu.core.*`;而 QSH 6.2.0.11 主插件把 TNML 重定位为 `com.ghostchu.quickshop.shade.tne.*`,服务器上不存在 `net.tnemc`,导致加载崩溃。

### 修复
- `addon/exchange/pom.xml` 的 shade 插件增加 `<shadedArtifactAttached>false</shadedArtifactAttached>`,让 shade 产物成为主构件,杜绝再次产出未 shade JAR。
- 正确 JAR:`outputs/exchange-6.3.0.0-SNAPSHOT-11-reload-with-market-add-remove.jar`(772,955 字节)。
- 已验证:与 `addon/exchange/target/Addon-Exchange-6.3.0.0-SNAPSHOT-11.jar` SHA-256 完全一致(`047a3aa6…`);对 JAR 内全部 class 扫描,`net/tnemc` 命中为 0,全部引用为 `shade/tne`。
- 注意:`addon/exchange/target/exchange-6.3.0.0-SNAPSHOT-11.jar` 仍是未 shade 的旧构件(危险,不要发给用户)。

### 用户侧待办(未做,需要提醒用户)
1. 用上面 outputs 里的 JAR 替换服务器 `plugins` 下旧 exchange JAR。
2. `markets.yml` 中 `minecraft_netherite_ingot.discovery-quantity: 8` 不合法(必须 ≥ min-quantity×10,即 ≥10,建议 16),否则重启会报 `invalid structural market rules`。
3. 重启服务器,若再报错把完整日志发回。

## 3. 用户最新反馈(当前要解决的核心问题):最新版"图根本没办法放下来"

用户原话:「主要是在最新版本他根本没办法放下来这张图啊」。尚未拿到服务器的具体现象/日志,以下是已查清的代码链路和怀疑点,下一步需要先复现确认。

### 命令与放置流程(已确认)
- 命令:`/qs exchange admin display map create <marketId> [1x1|2x1|2x2] [KLINE|LINE] [1h|6h|24h|7d]`
  - 入口:`AdminCommandRouter.display()` → `displayMap()`(`command/AdminCommandRouter.java:158-221`)。
  - 需要权限 `quickshop.exchange.admin.display`,且必须是玩家执行。
- 创建流程(`display/BukkitDisplayTargets.java`):
  1. 玩家**必须先在墙上放好空物品展示框**(同一墙面、连续矩形、框内无物品),然后准星对准其中任意一个框(射线距离 ≤ 8 格)。
  2. 插件按 1x1/2x1/2x2 从"最高、最左"的框开始铺开,校验 `layout()`(不连续/缺框 → `incomplete item frame wall`)。
  3. 校验所有框为空(`item frame wall contains occupied frames`),然后逐框生成 `FILLED_MAP`(Bukkit.createMap → prepareMap → setMapView)放入框内。
- 渲染挂载(`display/FoliaDisplayScheduler.java`):`MarketDisplayService.refresh` 后,`applyMapImage` 通过 `Bukkit.getMap(mapId)` 取 MapView,挂 `MarketMapRenderer` 并 `renderer.update(image)`。

### 怀疑点(需用户复现确认,优先级从高到低)
1. **操作方式不对**:用户可能没有先放物品框墙,或框不连续/不在同一面墙/框里有东西 → 命令只回通用错误 `display-operation-failed`(实际异常被吞,玩家看不到原因)。最可能的"放不下来"原因。
2. **准星没对准框**:`rayTraceEntities` 距离 8、尺寸 0.25,没对准就报 `no item frame target`(同样只显示通用错误)。
3. **地图空白**:若 `Bukkit.getMap(mapId)` 在 Paper 26.1.2 返回 null(或 MapMeta.setMapView 与 createMap 行为异常),刷新会静默 `complete(null)`,框里有地图但全空白。需要真机验证。
4. **Folia/QuickShop.folia() 兼容**:非 Folia Paper 上 `runAtEntityLater` 是否正常(旧版本能跑,基本可用,但值得确认)。
5. **displays 配置/上限**:`displays.max-map-walls`(默认 128)若被配成 0,会报 `display-limit-reached`。

### 需要向用户收集的信息(写进下次对话)
- 执行的确切命令;
- 服务器控制台在命令后的输出(有没有 `display-*` 相关报错);
- 是否先放了物品框、框的摆放方式(同墙?连续?是否为空);
- 框里最终是否有地图、地图是否空白;
- 服务器 Paper 版本与是否 Folia。

## 4. 图表绘制审查结论(已分析,未修)

用真实渲染器(构建 JAR 里的类)生成 5 张预览图并做像素级验证,发现以下问题(按优先级):

1. **标题无法显示中文**:display-name 全是中文(钻石/铁锭…),`compactText()` 剥离非 ASCII 后兜底成 "MARKET",所有图标题都一样;像素验证 58/59 像素匹配 "MARKET"。[MarketChartRenderer.java:465](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java:465),测试 [MarketChartRendererTest.java:132](addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRendererTest.java:132) 断言了该行为。
2. **PixelFont 缺字形**:缺 C/J/P/Q/U/V/X/Z,COAL→"OAL"、QUARTZ→"ART"。[PixelFont.java:12](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/PixelFont.java:12)
3. **时间轴 UTC 且无日期**:写死 `ZoneOffset.UTC`,只画 HH:mm;日线/周线首尾都是 "00:00"(像素验证左右标签完全相同),国内玩家还差 8 小时。[MarketChartRenderer.java:14](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java:14)
4. **价格刻度与网格不对齐**:网格 25/50/75%,标签只在 0/50/100%(实测 y=56/90/123 vs y=23/90/157)。[MarketChartRenderer.java:208](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartRenderer.java:208)
5. **超预算不降采样**:`AdaptiveChartIntervalSelector.downsample` 在 run 数 > target 时原样返回;2x2 预算 48 根,实测 120 根日线全部塞进 210px,120 列重叠、最密列 135px 全满。[AdaptiveChartIntervalSelector.java:88](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/AdaptiveChartIntervalSelector.java:88)
6. **下跌量柱与边框同色**:`VOLUME_FALL = 29` == `BORDER = 29`,几乎不可见。[MarketChartPalette.java:9](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketChartPalette.java:9)
7. **缓存永不命中(性能)**:snapshot fingerprint 含 `quote.asOf()`(纳秒级),每次刷新都重绘。[MarketDisplaySnapshot.java:39](addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/display/MarketDisplaySnapshot.java:39)
8. 小问题:可信价注入 `putIfAbsent` 时间戳相同价格不同时不更新;LINE 模式跨缺口连线;有可信价时最新价只有 4px 刻度无整线;2x1 布局无时间轴。

预览图(临时生成,可作参考):`C:\Users\ztrnb\.codex\visualizations\2026\07\26\019f9daf-4061-7412-8f8e-b106e1895c50\chartpreview\*.png`。生成程序在 `%TEMP%\chartpreview\ChartPreview.java`(用目标 JAR 当 classpath 直接渲染 PPM→PNG)。

## 5. 下一步要干嘛(给下一个 AI/电脑)

1. **首要**:找用户要"图放不下来"的具体现象/日志(见第 3 节收集清单),按怀疑点 1→5 排查并修复;修完在真机(或用户服务器)验证放置成功、地图有内容。
2. **其次(图表绘制)**:按第 4 节优先级修:①标题中文/字体 ②时间轴本地时区+日期 ③超预算降采样;再修刻度对齐、量柱颜色、缓存。
3. 每项改动补测试,最后跑 `mvn -o -pl addon/exchange -am "-Dapi.version=1.44" "-Dsurefire.failIfNoSpecifiedTests=false" test`,要求 635+ 全绿。
4. 构建正确 JAR 覆盖 `outputs/exchange-6.3.0.0-SNAPSHOT-11-reload-with-market-add-remove.jar`,并再次全类扫描 `net/tnemc` = 0。
5. 提醒用户服务器侧动作:替换 JAR + 改 `discovery-quantity` + 重启。

## 6. 环境注意事项(重要)

- 本机 GitHub 直连不通,git 命令必须带:`-c http.proxy=http://127.0.0.1:7897 -c credential.helper="!gh auth git-credential"`(gh 已登录 alright-qwq,有 repo 权限)。
- `apply_patch` 在本机报 Access denied,改文件用 PowerShell `Set-Content`(注意 CRLF);已提交的 pom 改动不要再动。
- 禁止 `git reset --hard` / `git clean` / 大范围 restore;工作树里其他分支/改动属于用户,保持只改 exchange 相关。
- 用户偏好:先分析、拿到证据再改;临时"停止开发写交接"指令以最新消息为准。
- 之前交接提到的"管理员热操作 GUI"等大功能暂不展开,当前焦点是"图放不下来"。
