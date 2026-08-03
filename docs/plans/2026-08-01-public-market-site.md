# Public Market Site Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为 Exchange 增加仅监听本机的公开只读行情 REST API 和可由 Caddy 托管的响应式静态行情站。

**Architecture:** 在现有 `ExchangeMarketDisplayDataSource` 之上增加不可变的公开行情目录和 HTTP 适配层，复用已经合并持久 Candle、实时 Candle、可信参考价点和内存最佳买卖价的快照。HTTP 服务使用 JDK `HttpServer`，默认绑定 `127.0.0.1:8765`，只开放 GET/HEAD，采用短时响应缓存、查询边界、并发限制和安全响应头；静态网页由 Caddy 独立托管并轮询同域 `/api/v1/public/*`。

**Tech Stack:** Java 21、JDK `com.sun.net.httpserver.HttpServer`、JUnit 5、AssertJ、原生 HTML/CSS/JavaScript、Canvas、Caddy。

---

### Task 1: 固定公开快照与 JSON 契约

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketCatalog.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketJson.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketCatalogTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketJsonTest.java`

**Steps:**
1. 写失败测试，要求市场 ID 严格校验、列表顺序稳定，快照包含 quote、真实 Candle、可信点和状态，不包含玩家、账户、订单或审计字段。
2. 运行 `mvn -pl addon/exchange -am -Dtest=PublicMarketCatalogTest,PublicMarketJsonTest test`，确认因类型缺失而失败。
3. 实现最小只读目录和无第三方依赖 JSON 编码器，BigDecimal 使用字符串防止精度丢失，Instant 使用 ISO-8601。
4. 再次运行定向测试，确认通过。

### Task 2: 实现安全的 HTTP 路由

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketHttpHandler.java`
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketWebConfig.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketHttpHandlerTest.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketWebConfigTest.java`

**Steps:**
1. 写失败测试覆盖 `/health`、市场列表、`snapshot?period=1h|6h|24h|7d`、未知市场、非法周期、非 GET/HEAD、查询字符串、缓存头和安全响应头。
2. 验证测试按预期失败。
3. 实现路由和统一错误 JSON；限制 URL 长度和查询参数，只允许受支持周期，响应设置 `Content-Type`、`Cache-Control`、`X-Content-Type-Options`、`Content-Security-Policy`、`Referrer-Policy`。
4. 运行定向测试并保持全绿。

### Task 3: 实现服务生命周期、缓存与并发保护

**Files:**
- Create: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketWebServer.java`
- Test: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketWebServerTest.java`

**Steps:**
1. 写失败测试覆盖绑定 `127.0.0.1`、随机测试端口、重复关闭、关闭后端口释放、缓存窗口内合并重复读取、并发上限返回 503。
2. 验证测试失败。
3. 使用 JDK `HttpServer` 和固定 daemon 线程池实现；禁止非回环绑定，除非显式 `allow-non-loopback`，并对该危险配置启动失败。
4. 运行定向测试。

### Task 4: 接入 Exchange 运行时

**Files:**
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactory.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntime.java`
- Modify: `addon/exchange/src/main/resources/config.yml`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeFactoryTest.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/runtime/ExchangeRuntimeTest.java`
- Modify: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ModuleSmokeTest.java`

**Steps:**
1. 写失败测试固定默认配置：`enabled: false`、`127.0.0.1`、`8765`、3 秒缓存、2 个读取线程、受支持周期。
2. 写失败生命周期测试，要求 Web 服务先于显示/dispatcher/writer 关闭，失败可重试且不释放后续资源。
3. 在工厂中保留 `ExchangeMarketDisplayDataSource` 实例，构造公开目录；配置启用时启动 Web 服务并加入启动失败逆序清理。
4. 将 Web 服务纳入 `ExchangeRuntime.close()` 的幂等阶段。
5. 运行 runtime、factory、smoke 定向测试。

### Task 5: 实现静态公开行情站

**Files:**
- Create: `addon/exchange/src/main/resources/web/index.html`
- Create: `addon/exchange/src/main/resources/web/app.css`
- Create: `addon/exchange/src/main/resources/web/app.js`
- Create: `addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/web/PublicMarketAssetsTest.java`
- Modify: `addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java`

**Steps:**
1. 写失败资源测试，要求三个文件进入 JAR，并由首次启动资源清单导出到插件数据目录 `web/`，现有管理员改动不覆盖自定义文件。
2. 实现移动端优先网页：市场选择、状态、现价、可信价、买一卖一、24h 指标、1h/6h/24h/7d 切换、Canvas 真实价格线/可信价格线/成交量、加载/离线/过期状态。
3. 网页仅请求相对路径 `/api/v1/public/...`，不依赖外部 CDN，不设置任何写请求。
4. 运行资源测试。

### Task 6: 文档、全量验证与交付

**Files:**
- Create: `docs/exchange-public-market-site.md`
- Modify: `docs/exchange-addon-change-summary.md`

**Steps:**
1. 写 Linux + MCDR + Caddy 部署说明，包含 DNS、80/443、防火墙、Caddyfile、API 健康检查、重启表现和故障排查。
2. 运行 Web 定向测试。
3. 运行 Exchange 全量测试并汇总 Surefire 数量。
4. 运行离线发布构建和 `git diff --check`。
5. 验收 JAR 的 ZIP、`plugin.yml`、Web 类、静态资源和测试类排除。
6. 复制为新的 `outputs/Addon-Exchange-...-public-market-site.jar` 并交付 JAR、网页资源和部署文档。
