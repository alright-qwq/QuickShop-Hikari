# Exchange Phase 1 Matching Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 新增可独立测试、完全不依赖 Bukkit 的中央订单簿核心，支持价格时间优先、限价/市价撮合、手续费、价格保护、熔断和幂等串行命令。

**Architecture:** addon/exchange 是独立 Maven Addon；core 包只使用 Java 21 类型，Bukkit 入口只负责生命周期。每个市场由单线程 MarketDispatcher 串行执行，MatchingEngine 是确定性内存状态机，后续阶段通过端口接口把同一结果原子写入数据库。

**Tech Stack:** Java 21 records/sealed interfaces、Maven、JUnit Jupiter 5.14.2、AssertJ 3.27.6、JDK concurrent collections

---

## 文件结构

- Modify: pom.xml — 注册 addon/exchange 聚合模块。
- Create: addon/exchange/pom.xml — Addon 编译、依赖与测试运行时。
- Create: addon/exchange/src/main/resources/plugin.yml — QuickShop 依赖、Folia 声明和权限。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java — 暂时只提供安全启停，Phase 4 再装配服务。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/*.java — 市场、订单、成交和拒绝原因等不可变领域类型。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java — 两侧价格档、FIFO 队列与 O(log n) 撤单索引。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/*.java — 撮合、资产预留和手续费计算。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/*.java — 5 分钟 VWAP、价格笼子、滑点和熔断。
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/*.java — 每市场串行队列与 requestId 幂等端口。
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/**/*.java — 确定性、随机性质和性能回归测试。

### Task 1: 建立 Addon 与测试运行时

**Files:**
- Modify: pom.xml:494-516
- Create: addon/exchange/pom.xml
- Create: addon/exchange/src/main/resources/plugin.yml
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ModuleSmokeTest.java

- [ ] **Step 1: 写模块存在性红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleSmokeTest {
  @Test
  void exposesAddonMainClass() {
    assertThat(Main.class.getName())
        .isEqualTo("com.ghostchu.quickshop.addon.exchange.Main");
  }
}
~~~

- [ ] **Step 2: 先运行测试并确认模块尚不存在**

Run: mvn -pl addon/exchange -am -DskipTests=false -Dtest=ModuleSmokeTest test

Expected: FAIL，Maven 报 selected project not found 或 addon/exchange/pom.xml 不存在。

- [ ] **Step 3: 注册模块并写最小 Addon**

在根 pom.xml 的 addon/discount 后加入：

~~~xml
<module>addon/exchange</module>
~~~

addon/exchange/pom.xml 使用以下完整内容：

~~~xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.ghostchu</groupId>
    <artifactId>quickshop-hikari</artifactId>
    <version>6.3.0.0-SNAPSHOT-11</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <groupId>com.ghostchu.quickshop.addon</groupId>
  <artifactId>exchange</artifactId>
  <packaging>takari-jar</packaging>
  <name>Addon-Exchange</name>
  <description>Central limit order book for fungible Minecraft items</description>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.3</version>
      </plugin>
    </plugins>
    <resources>
      <resource>
        <directory>src/main/resources</directory>
        <filtering>true</filtering>
      </resource>
    </resources>
  </build>
  <dependencies>
    <dependency>
      <groupId>io.papermc.paper</groupId>
      <artifactId>paper-api</artifactId>
      <version>${depend.paper}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.ghostchu</groupId>
      <artifactId>quickshop-bukkit</artifactId>
      <version>${project.parent.version}</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.14.2</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <version>3.27.6</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
~~~

addon/exchange/src/main/resources/plugin.yml：

~~~yaml
name: qssuite-exchange
version: '${project.version}'
main: com.ghostchu.quickshop.addon.${project.artifactId}.Main
folia-supported: true
api-version: '1.20'
depend:
  - QuickShop-Hikari
authors: [ Ghost_chu ]
permissions:
  quickshop.exchange.use:
    default: true
~~~

addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java：

~~~java
package com.ghostchu.quickshop.addon.exchange;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  @Override
  public void onEnable() {
    saveDefaultConfig();
  }
}
~~~

- [ ] **Step 4: 运行烟雾测试并确认通过**

Run: mvn -pl addon/exchange -am -DskipTests=false -Dtest=ModuleSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test

Expected: BUILD SUCCESS，ModuleSmokeTest 1 test passed。

- [ ] **Step 5: 提交脚手架**

~~~bash
git add pom.xml addon/exchange/pom.xml addon/exchange/src/main/resources/plugin.yml addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/Main.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/ModuleSmokeTest.java
git commit -m "feat(exchange): scaffold addon module"
~~~

### Task 2: 定义市场、订单与成交领域类型

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/MarketStatus.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/OrderSide.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/OrderType.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/TimeInForce.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/OrderStatus.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/MarketRules.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/Order.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/Trade.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model/TimeOrderedIdGenerator.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/model/DomainValidationTest.java

- [ ] **Step 1: 写金额精度、TIF 和数量约束的红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainValidationTest {
  @Test
  void rejectsPriceOffTick() {
    assertThatThrownBy(() -> rules().validatePrice(new BigDecimal("10.02")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("price is not aligned to tickSize");
  }

  @Test
  void marketOrderMustBeIoc() {
    assertThatThrownBy(() -> new Order(
        UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.GTC, null,
        new BigDecimal("12.00"), 5, 5, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH))
        .hasMessage("market order requires IOC");
  }

  @Test
  void generatesMonotonicVersionSevenIdsWithinOneMillisecond() {
    TimeOrderedIdGenerator ids =
        new TimeOrderedIdGenerator(() -> 1_721_952_000_000L, new java.util.Random(7));
    UUID first = ids.get();
    UUID second = ids.get();
    assertThat(first.version()).isEqualTo(7);
    assertThat(first.variant()).isEqualTo(2);
    assertThat(second.compareTo(first)).isPositive();
  }

  private static MarketRules rules() {
    return new MarketRules("diamond-usd", "USD", new BigDecimal("100.00"),
        new BigDecimal("1.00"), new BigDecimal("10000.00"), new BigDecimal("0.05"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }
}
~~~

- [ ] **Step 2: 运行测试确认领域类型缺失**

Run: mvn -pl addon/exchange -Dtest=DomainValidationTest test

Expected: FAIL，编译器报告 MarketRules、Order 等符号不存在。

- [ ] **Step 3: 写最小且完整的领域定义**

创建单值枚举：

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;
public enum MarketStatus { OPEN, HALTED, PAUSED, RECOVERING, CLOSED }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;
public enum OrderSide { BUY, SELL }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;
public enum OrderType { LIMIT, MARKET }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;
public enum TimeInForce { GTC, IOC }
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;
public enum OrderStatus { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }
~~~

MarketRules 的构造器统一检查正值、费率为 0..1、priceScale 与 tickSize：

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record MarketRules(
    String marketId, String currencyId, BigDecimal basePrice,
    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal tickSize,
    long minQuantity, long maxQuantity, int priceScale,
    BigDecimal makerFeeRate, BigDecimal takerFeeRate) {

  public MarketRules {
    if (marketId == null || marketId.isBlank() || currencyId == null || currencyId.isBlank()) {
      throw new IllegalArgumentException("market and currency are required");
    }
    if (minQuantity <= 0 || maxQuantity < minQuantity || priceScale < 0) {
      throw new IllegalArgumentException("invalid quantity or scale");
    }
    requirePositive(basePrice, "basePrice");
    requirePositive(minPrice, "minPrice");
    requirePositive(maxPrice, "maxPrice");
    requirePositive(tickSize, "tickSize");
    if (minPrice.compareTo(maxPrice) >= 0) {
      throw new IllegalArgumentException("minPrice must be below maxPrice");
    }
    validateRate(makerFeeRate);
    validateRate(takerFeeRate);
  }

  public void validatePrice(BigDecimal price) {
    if (price == null || price.scale() > priceScale
        || price.compareTo(minPrice) < 0 || price.compareTo(maxPrice) > 0) {
      throw new IllegalArgumentException("price outside market bounds");
    }
    BigDecimal ticks = price.divide(tickSize, 0, RoundingMode.DOWN);
    if (ticks.multiply(tickSize).compareTo(price) != 0) {
      throw new IllegalArgumentException("price is not aligned to tickSize");
    }
  }

  public void validateQuantity(long quantity) {
    if (quantity < minQuantity || quantity > maxQuantity) {
      throw new IllegalArgumentException("quantity outside market bounds");
    }
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) throw new IllegalArgumentException(name + " must be positive");
  }

  private static void validateRate(BigDecimal rate) {
    if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("fee rate outside 0..1");
    }
  }
}
~~~

订单、成交和转账的服务端 ID 使用单调 UUIDv7 生成器；玩家 requestId 可以是客户端 UUIDv4：

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class TimeOrderedIdGenerator implements Supplier<UUID> {
  private final LongSupplier epochMillis;
  private final RandomGenerator random;
  private long lastMillis = -1;
  private int sequence;

  public TimeOrderedIdGenerator(LongSupplier epochMillis, RandomGenerator random) {
    this.epochMillis = epochMillis;
    this.random = random;
  }

  @Override
  public synchronized UUID get() {
    long millis = Math.max(epochMillis.getAsLong(), lastMillis);
    if (millis == lastMillis) {
      sequence = (sequence + 1) & 0x0fff;
      if (sequence == 0) millis = ++lastMillis;
    } else {
      lastMillis = millis;
      sequence = random.nextInt(0x1000);
    }
    long most = ((millis & 0x0000ffffffffffffL) << 16)
        | 0x7000L | sequence;
    long least = (random.nextLong() & 0x3fffffffffffffffL)
        | 0x8000000000000000L;
    return new UUID(most, least);
  }
}
~~~

Order 保持不可变，部分成交通过 withRemaining 返回新值：

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Order(
    UUID orderId, UUID requestId, String marketId, UUID accountId,
    OrderSide side, OrderType type, TimeInForce timeInForce,
    BigDecimal limitPrice, BigDecimal slippageBoundary,
    long originalQuantity, long remainingQuantity, OrderStatus status,
    long prioritySequence, long configVersion, long feeVersion,
    Instant createdAt, Instant updatedAt) {

  public Order {
    if (orderId == null || requestId == null || accountId == null || marketId == null) {
      throw new IllegalArgumentException("order identity is required");
    }
    if (originalQuantity <= 0 || remainingQuantity < 0 || remainingQuantity > originalQuantity) {
      throw new IllegalArgumentException("invalid remaining quantity");
    }
    if (type == OrderType.LIMIT && (limitPrice == null || timeInForce != TimeInForce.GTC)) {
      throw new IllegalArgumentException("limit order requires price and GTC");
    }
    if (type == OrderType.MARKET && (slippageBoundary == null || timeInForce != TimeInForce.IOC)) {
      throw new IllegalArgumentException("market order requires IOC");
    }
  }

  public Order withRemaining(long remaining, Instant now) {
    OrderStatus next = remaining == 0 ? OrderStatus.FILLED
        : remaining == originalQuantity ? OrderStatus.OPEN : OrderStatus.PARTIALLY_FILLED;
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remaining, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }

  public Order withStatus(OrderStatus next, Instant now) {
    return new Order(orderId, requestId, marketId, accountId, side, type, timeInForce,
        limitPrice, slippageBoundary, originalQuantity, remainingQuantity, next,
        prioritySequence, configVersion, feeVersion, createdAt, now);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Trade(
    UUID tradeId, String marketId, UUID makerOrderId, UUID takerOrderId,
    UUID buyerAccountId, UUID sellerAccountId, BigDecimal price, long quantity,
    BigDecimal makerFee, BigDecimal takerFee, long matchSequence, Instant executedAt) {
  public Trade {
    if (tradeId == null || quantity <= 0 || price == null || price.signum() <= 0) {
      throw new IllegalArgumentException("invalid trade");
    }
  }
}
~~~

- [ ] **Step 4: 运行领域测试**

Run: mvn -pl addon/exchange -Dtest=DomainValidationTest test

Expected: PASS，2 tests passed。

- [ ] **Step 5: 提交领域模型**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/model addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/model
git commit -m "feat(exchange): define order book domain"
~~~

### Task 3: 实现价格优先、同价时间优先订单簿

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBookTest.java

- [ ] **Step 1: 写价格和 FIFO 红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderBookTest {
  @Test
  void choosesHighestBidLowestAskAndOldestAtPrice() {
    OrderBook book = new OrderBook();
    Order bid100 = order(OrderSide.BUY, "100.00", 1);
    Order bid101Old = order(OrderSide.BUY, "101.00", 2);
    Order bid101New = order(OrderSide.BUY, "101.00", 3);
    Order ask103 = order(OrderSide.SELL, "103.00", 4);
    book.add(bid100);
    book.add(bid101Old);
    book.add(bid101New);
    book.add(ask103);

    assertThat(book.best(OrderSide.BUY)).contains(bid101Old);
    assertThat(book.best(OrderSide.SELL)).contains(ask103);
    assertThat(book.cancel(bid101Old.orderId())).contains(bid101Old);
    assertThat(book.best(OrderSide.BUY)).contains(bid101New);
  }

  private static Order order(OrderSide side, String price, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        10, 10, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
~~~

- [ ] **Step 2: 运行并确认 OrderBook 缺失**

Run: mvn -pl addon/exchange -Dtest=OrderBookTest test

Expected: FAIL，编译器报告 OrderBook 不存在。

- [ ] **Step 3: 写 TreeMap 价格档和稳定撤单索引**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;

import java.math.BigDecimal;
import java.util.*;

public final class OrderBook {
  private final NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> bids =
      new TreeMap<>(Comparator.reverseOrder());
  private final NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> asks = new TreeMap<>();
  private final Map<UUID, BigDecimal> priceByOrder = new HashMap<>();
  private final Map<UUID, OrderSide> sideByOrder = new HashMap<>();

  public void add(Order order) {
    if (order.limitPrice() == null || priceByOrder.containsKey(order.orderId())) {
      throw new IllegalArgumentException("resting order requires unique id and limit price");
    }
    levels(order.side()).computeIfAbsent(order.limitPrice(), ignored -> new LinkedHashMap<>())
        .put(order.orderId(), order);
    priceByOrder.put(order.orderId(), order.limitPrice());
    sideByOrder.put(order.orderId(), order.side());
  }

  public Optional<Order> best(OrderSide side) {
    var levels = levels(side);
    if (levels.isEmpty()) return Optional.empty();
    return levels.firstEntry().getValue().values().stream().findFirst();
  }

  public Optional<Order> cancel(UUID orderId) {
    BigDecimal price = priceByOrder.remove(orderId);
    OrderSide side = sideByOrder.remove(orderId);
    if (price == null || side == null) return Optional.empty();
    LinkedHashMap<UUID, Order> level = levels(side).get(price);
    Order removed = level.remove(orderId);
    if (level.isEmpty()) levels(side).remove(price);
    return Optional.ofNullable(removed);
  }

  public void replaceRemaining(Order order) {
    BigDecimal price = priceByOrder.get(order.orderId());
    OrderSide side = sideByOrder.get(order.orderId());
    if (price == null || side == null) throw new IllegalArgumentException("order is not resting");
    levels(side).get(price).replace(order.orderId(), order);
  }

  public int openOrderCount() {
    return priceByOrder.size();
  }

  private NavigableMap<BigDecimal, LinkedHashMap<UUID, Order>> levels(OrderSide side) {
    return side == OrderSide.BUY ? bids : asks;
  }
}
~~~

- [ ] **Step 4: 运行订单簿测试**

Run: mvn -pl addon/exchange -Dtest=OrderBookTest test

Expected: PASS，1 test passed。

- [ ] **Step 5: 提交订单簿**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBookTest.java
git commit -m "feat(exchange): add price-time order book"
~~~

### Task 4: 实现限价撮合、Maker 价格和部分成交

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchResult.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingEngine.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/matching/LimitMatchingTest.java

- [ ] **Step 1: 写跨价格档与 Maker 成交价红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LimitMatchingTest {
  @Test
  void fillsAcrossMakersAtTheirPricesAndRestsRemainder() {
    AtomicLong matches = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(new OrderBook(), matches::incrementAndGet,
        () -> Instant.parse("2026-07-26T00:00:00Z"), UUID::randomUUID);
    engine.submit(order(OrderSide.SELL, "99.00", 4, 1));
    engine.submit(order(OrderSide.SELL, "100.00", 4, 2));

    MatchResult result = engine.submit(order(OrderSide.BUY, "101.00", 10, 3));

    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("99.00"), new BigDecimal("100.00"));
    assertThat(result.trades()).extracting(Trade::quantity).containsExactly(4L, 4L);
    assertThat(result.finalOrder().remainingQuantity()).isEqualTo(2);
    assertThat(result.rested()).isTrue();
  }

  private static Order order(OrderSide side, String price, long quantity, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
~~~

- [ ] **Step 2: 运行并确认撮合类型缺失**

Run: mvn -pl addon/exchange -Dtest=LimitMatchingTest test

Expected: FAIL，编译器报告 MatchingEngine 和 MatchResult 不存在。

- [ ] **Step 3: 实现确定性撮合循环**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;

import java.util.List;

public record MatchResult(Order finalOrder, List<Order> changedMakers,
                          List<Trade> trades, boolean rested, boolean selfTradeRejected) {
  public MatchResult {
    changedMakers = List.copyOf(changedMakers);
    trades = List.copyOf(trades);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class MatchingEngine {
  private final OrderBook book;
  private final LongSupplier matchSequence;
  private final Supplier<Instant> now;
  private final Supplier<UUID> tradeIds;

  public MatchingEngine(OrderBook book, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds) {
    this.book = book;
    this.matchSequence = matchSequence;
    this.now = now;
    this.tradeIds = tradeIds;
  }

  public MatchResult submit(Order incoming) {
    ArrayList<Order> makers = new ArrayList<>();
    ArrayList<Trade> trades = new ArrayList<>();
    Order taker = incoming;
    OrderSide opposite = incoming.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    while (taker.remainingQuantity() > 0) {
      Order maker = book.best(opposite).orElse(null);
      if (maker == null || !crosses(taker, maker)) break;
      if (maker.accountId().equals(taker.accountId())) {
        return new MatchResult(taker, makers, trades, false, true);
      }
      long quantity = Math.min(taker.remainingQuantity(), maker.remainingQuantity());
      Instant executedAt = now.get();
      taker = taker.withRemaining(taker.remainingQuantity() - quantity, executedAt);
      Order changedMaker = maker.withRemaining(maker.remainingQuantity() - quantity, executedAt);
      if (changedMaker.remainingQuantity() == 0) {
        book.cancel(maker.orderId());
      } else {
        book.replaceRemaining(changedMaker);
      }
      makers.add(changedMaker);
      UUID buyer = incoming.side() == OrderSide.BUY ? incoming.accountId() : maker.accountId();
      UUID seller = incoming.side() == OrderSide.SELL ? incoming.accountId() : maker.accountId();
      trades.add(new Trade(tradeIds.get(), incoming.marketId(), maker.orderId(), incoming.orderId(),
          buyer, seller, maker.limitPrice(), quantity,
          java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
          matchSequence.getAsLong(), executedAt));
    }
    if (taker.type() == OrderType.MARKET && taker.remainingQuantity() > 0) {
      taker = taker.withStatus(OrderStatus.CANCELLED, now.get());
    }
    boolean rested = taker.remainingQuantity() > 0 && taker.type() == OrderType.LIMIT;
    if (rested) book.add(taker);
    return new MatchResult(taker, makers, trades, rested, false);
  }

  private static boolean crosses(Order taker, Order maker) {
    java.math.BigDecimal boundary =
        taker.type() == OrderType.LIMIT ? taker.limitPrice() : taker.slippageBoundary();
    return taker.side() == OrderSide.BUY
        ? maker.limitPrice().compareTo(boundary) <= 0
        : maker.limitPrice().compareTo(boundary) >= 0;
  }
}
~~~

- [ ] **Step 4: 运行撮合测试和前序回归**

Run: mvn -pl addon/exchange -Dtest=OrderBookTest,LimitMatchingTest test

Expected: PASS，价格顺序、FIFO、部分成交和 Maker 价格断言全部通过。

- [ ] **Step 5: 提交限价撮合**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/matching
git commit -m "feat(exchange): match limit orders"
~~~

### Task 5: 加入手续费、资产预留、市价 IOC 与自成交保护

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/FeeCalculator.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/Reservation.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/ReservationCalculator.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingEngine.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/matching/FeesMarketAndSelfTradeTest.java

- [ ] **Step 1: 写手续费向上舍入、市价余量取消和自成交红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FeesMarketAndSelfTradeTest {
  @Test
  void roundsFeeUpToCurrencyScaleAndCancelsIocRemainder() {
    FeeCalculator fees = new FeeCalculator(2);
    assertThat(fees.fee(new BigDecimal("1.01"), new BigDecimal("0.001")))
        .isEqualByComparingTo("0.01");

    OrderBook book = new OrderBook();
    AtomicLong sequence = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(
        book, sequence::incrementAndGet, () -> Instant.EPOCH, UUID::randomUUID);
    engine.submit(limit(OrderSide.SELL, "100.00", 2, UUID.randomUUID(), 1));
    Order market = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        OrderSide.BUY, OrderType.MARKET, TimeInForce.IOC, null, new BigDecimal("105.00"),
        5, 5, OrderStatus.OPEN, 2, 1, 1, Instant.EPOCH, Instant.EPOCH);

    MatchResult result = engine.submit(market);

    assertThat(result.trades()).hasSize(1);
    assertThat(result.finalOrder().remainingQuantity()).isEqualTo(3);
    assertThat(result.rested()).isFalse();
    assertThat(book.openOrderCount()).isZero();
  }

  @Test
  void rejectsIncomingSideWhenAccountsMatch() {
    UUID owner = UUID.randomUUID();
    MatchingEngine engine = new MatchingEngine(
        new OrderBook(), () -> 1, () -> Instant.EPOCH, UUID::randomUUID);
    engine.submit(limit(OrderSide.SELL, "100.00", 2, owner, 1));

    MatchResult result = engine.submit(limit(OrderSide.BUY, "100.00", 2, owner, 2));

    assertThat(result.selfTradeRejected()).isTrue();
    assertThat(result.trades()).isEmpty();
  }

  private static Order limit(OrderSide side, String price, long quantity, UUID account, long sequence) {
    return new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", account,
        side, OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        quantity, quantity, OrderStatus.OPEN, sequence, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
~~~

- [ ] **Step 2: 运行并确认费用与预留类型缺失**

Run: mvn -pl addon/exchange -Dtest=FeesMarketAndSelfTradeTest test

Expected: FAIL，编译器报告 FeeCalculator 不存在。

- [ ] **Step 3: 实现费用和最坏情况预留**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record FeeCalculator(int currencyScale) {
  public BigDecimal fee(BigDecimal notional, BigDecimal rate) {
    return notional.multiply(rate).setScale(currencyScale, RoundingMode.UP);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import java.math.BigDecimal;

public record Reservation(BigDecimal frozenCurrency, long frozenQuantity) {
  public Reservation {
    if (frozenCurrency.signum() < 0 || frozenQuantity < 0) {
      throw new IllegalArgumentException("reservation cannot be negative");
    }
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.model.*;

import java.math.BigDecimal;

public final class ReservationCalculator {
  private final FeeCalculator fees;

  public ReservationCalculator(FeeCalculator fees) {
    this.fees = fees;
  }

  public Reservation reserve(Order order, MarketRules rules) {
    if (order.side() == OrderSide.SELL) return new Reservation(BigDecimal.ZERO, order.remainingQuantity());
    BigDecimal maximumPrice = order.type() == OrderType.LIMIT
        ? order.limitPrice() : order.slippageBoundary();
    BigDecimal notional = maximumPrice.multiply(BigDecimal.valueOf(order.remainingQuantity()));
    return new Reservation(notional.add(fees.fee(notional, rules.takerFeeRate())), 0);
  }
}
~~~

修改 MatchingEngine：构造器增加 FeeCalculator 和 MarketRules；构造 Trade 时根据 maker.side 决定买卖双方的 maker/taker 费。为避免调用方歧义，最终构造器固定为：

~~~java
public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees,
                      LongSupplier matchSequence, Supplier<Instant> now,
                      Supplier<UUID> tradeIds)
~~~

Trade 创建代码替换为：

~~~java
BigDecimal notional = maker.limitPrice().multiply(BigDecimal.valueOf(quantity));
BigDecimal makerFee = fees.fee(notional, rules.makerFeeRate());
BigDecimal takerFee = fees.fee(notional, rules.takerFeeRate());
trades.add(new Trade(tradeIds.get(), incoming.marketId(), maker.orderId(), incoming.orderId(),
    buyer, seller, maker.limitPrice(), quantity, makerFee, takerFee,
    matchSequence.getAsLong(), executedAt));
~~~

同步修改三个测试的构造调用，统一传入：

~~~java
new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
    sequence::incrementAndGet, () -> Instant.EPOCH, UUID::randomUUID)
~~~

把公共 rules 工厂创建在测试源：

~~~java
package com.ghostchu.quickshop.addon.exchange.core;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import java.math.BigDecimal;

public final class TestFixtures {
  private TestFixtures() {}
  public static MarketRules rules() {
    return new MarketRules("diamond-usd", "USD", new BigDecimal("100.00"),
        new BigDecimal("1.00"), new BigDecimal("10000.00"), new BigDecimal("0.01"),
        1, 10000, 2, new BigDecimal("0.001"), new BigDecimal("0.002"));
  }
}
~~~

- [ ] **Step 4: 运行整个 core 测试集**

Run: mvn -pl addon/exchange test

Expected: PASS；市价单不挂簿、自成交不成交、手续费以货币精度向上舍入。

- [ ] **Step 5: 提交费用和 IOC**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core
git commit -m "feat(exchange): add fees reservations and market IOC"
~~~

### Task 6: 实现参考价、价格笼子和两级熔断

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/PriceSample.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/ReferencePriceTracker.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/RiskLimits.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/CircuitBreaker.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk/TradePermission.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java
- Modify: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingEngine.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk/MarketRiskTest.java

- [ ] **Step 1: 写发现期平滑、20% 笼子和两级暂停红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchResult;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MarketRiskTest {
  @Test
  void blendsBasePriceUntilDiscoveryVolumeReached() {
    ReferencePriceTracker tracker =
        new ReferencePriceTracker(new BigDecimal("100.00"), 100, Duration.ofMinutes(5), 2);
    tracker.record(new BigDecimal("120.00"), 50, Instant.EPOCH);
    assertThat(tracker.referenceAt(Instant.EPOCH)).isEqualByComparingTo("110.00");
  }

  @Test
  void cagesPriceAndEscalatesBreaker() {
    RiskLimits limits = RiskLimits.defaults();
    assertThat(limits.insideCage(new BigDecimal("120.00"), new BigDecimal("100.00"))).isTrue();
    assertThat(limits.insideCage(new BigDecimal("120.01"), new BigDecimal("100.00"))).isFalse();

    CircuitBreaker breaker = new CircuitBreaker(limits);
    Instant now = Instant.parse("2026-07-26T00:00:00Z");
    assertThat(breaker.onPrice(new BigDecimal("111.00"), new BigDecimal("100.00"), now).haltUntil())
        .contains(now.plus(Duration.ofMinutes(2)));
    breaker.resume(now.plus(Duration.ofMinutes(2)));
    assertThat(breaker.onPrice(new BigDecimal("121.00"), new BigDecimal("100.00"),
        now.plus(Duration.ofMinutes(3))).haltUntil())
        .contains(now.plus(Duration.ofMinutes(13)));
  }

  @Test
  void protectedBestLevelStaysInBookWhileNextExecutableLevelTrades() {
    RiskLimits limits = RiskLimits.defaults();
    java.util.function.Predicate<BigDecimal> guard =
        price -> limits.insideCage(price, new BigDecimal("100.00"));
    com.ghostchu.quickshop.addon.exchange.core.book.OrderBook book =
        new com.ghostchu.quickshop.addon.exchange.core.book.OrderBook();
    Order protectedAsk = order(OrderSide.SELL, "70.00", 1);
    Order executableAsk = order(OrderSide.SELL, "90.00", 2);
    book.add(protectedAsk);
    book.add(executableAsk);
    var engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        () -> 1, () -> Instant.EPOCH, java.util.UUID::randomUUID, guard);

    MatchResult result = engine.submit(order(OrderSide.BUY, "100.00", 3));

    assertThat(result.trades()).extracting(Trade::price)
        .containsExactly(new BigDecimal("90.00"));
    assertThat(book.snapshot()).contains(protectedAsk);
  }

  private static Order order(OrderSide side, String price, long priority) {
    return new Order(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
        "diamond-usd", java.util.UUID.randomUUID(), side,
        OrderType.LIMIT, TimeInForce.GTC, new BigDecimal(price), null,
        1, 1, OrderStatus.OPEN, priority, 1, 1, Instant.EPOCH, Instant.EPOCH);
  }
}
~~~

- [ ] **Step 2: 运行并确认 risk 类型缺失**

Run: mvn -pl addon/exchange -Dtest=MarketRiskTest test

Expected: FAIL，编译器报告 ReferencePriceTracker、RiskLimits 和 CircuitBreaker 不存在。

- [ ] **Step 3: 实现固定窗口 VWAP 和风险参数**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceSample(BigDecimal price, long quantity, Instant occurredAt) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.*;
import java.time.*;
import java.util.ArrayDeque;

public final class ReferencePriceTracker {
  private final BigDecimal basePrice;
  private final long discoveryQuantity;
  private final Duration window;
  private final int scale;
  private final ArrayDeque<PriceSample> samples = new ArrayDeque<>();
  private long cumulativeDiscoveryQuantity;

  public ReferencePriceTracker(BigDecimal basePrice, long discoveryQuantity,
                               Duration window, int scale) {
    if (discoveryQuantity < 10) throw new IllegalArgumentException("discovery quantity must be at least 10");
    this.basePrice = basePrice;
    this.discoveryQuantity = discoveryQuantity;
    this.window = window;
    this.scale = scale;
  }

  public void record(BigDecimal price, long quantity, Instant occurredAt) {
    samples.addLast(new PriceSample(price, quantity, occurredAt));
    cumulativeDiscoveryQuantity = Math.addExact(cumulativeDiscoveryQuantity, quantity);
  }

  public BigDecimal referenceAt(Instant now) {
    Instant cutoff = now.minus(window);
    while (!samples.isEmpty() && samples.peekFirst().occurredAt().isBefore(cutoff)) samples.removeFirst();
    if (samples.isEmpty()) return basePrice;
    BigDecimal notional = BigDecimal.ZERO;
    long volume = 0;
    for (PriceSample sample : samples) {
      notional = notional.add(sample.price().multiply(BigDecimal.valueOf(sample.quantity())));
      volume = Math.addExact(volume, sample.quantity());
    }
    BigDecimal vwap = notional.divide(BigDecimal.valueOf(volume), scale + 6, RoundingMode.HALF_UP);
    BigDecimal ratio = BigDecimal.valueOf(Math.min(cumulativeDiscoveryQuantity, discoveryQuantity))
        .divide(BigDecimal.valueOf(discoveryQuantity), scale + 6, RoundingMode.HALF_UP);
    return basePrice.multiply(BigDecimal.ONE.subtract(ratio)).add(vwap.multiply(ratio))
        .setScale(scale, RoundingMode.HALF_UP);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Duration;

public record RiskLimits(
    BigDecimal cageRatio, BigDecimal defaultSlippage, BigDecimal maximumSlippage,
    BigDecimal levelOneMove, Duration levelOneHalt,
    BigDecimal levelTwoMove, Duration levelTwoHalt) {

  public static RiskLimits defaults() {
    return new RiskLimits(new BigDecimal("0.20"), new BigDecimal("0.05"),
        new BigDecimal("0.20"), new BigDecimal("0.10"), Duration.ofMinutes(2),
        new BigDecimal("0.20"), Duration.ofMinutes(10));
  }

  public boolean insideCage(BigDecimal price, BigDecimal reference) {
    BigDecimal lower = reference.multiply(BigDecimal.ONE.subtract(cageRatio));
    BigDecimal upper = reference.multiply(BigDecimal.ONE.add(cageRatio));
    return price.compareTo(lower) >= 0 && price.compareTo(upper) <= 0;
  }
}
~~~

- [ ] **Step 4: 实现熔断状态机**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.time.Instant;
import java.util.Optional;

public record TradePermission(boolean allowed, Optional<Instant> haltUntil, int level) {
  public static TradePermission open() {
    return new TradePermission(true, Optional.empty(), 0);
  }
  public static TradePermission halted(Instant until, int level) {
    return new TradePermission(false, Optional.of(until), level);
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public final class CircuitBreaker {
  private final RiskLimits limits;
  private int lastLevel;
  private Instant haltedUntil;

  public CircuitBreaker(RiskLimits limits) {
    this.limits = limits;
  }

  public TradePermission onPrice(BigDecimal price, BigDecimal reference, Instant now) {
    if (haltedUntil != null && now.isBefore(haltedUntil)) {
      return TradePermission.halted(haltedUntil, lastLevel);
    }
    BigDecimal move = price.subtract(reference).abs()
        .divide(reference, 12, RoundingMode.HALF_UP);
    if (lastLevel >= 1 && move.compareTo(limits.levelTwoMove()) >= 0) {
      lastLevel = 2;
      haltedUntil = now.plus(limits.levelTwoHalt());
      return TradePermission.halted(haltedUntil, 2);
    }
    if (move.compareTo(limits.levelOneMove()) >= 0) {
      lastLevel = 1;
      haltedUntil = now.plus(limits.levelOneHalt());
      return TradePermission.halted(haltedUntil, 1);
    }
    return TradePermission.open();
  }

  public void resume(Instant now) {
    if (haltedUntil == null || now.isBefore(haltedUntil)) {
      throw new IllegalStateException("halt has not expired");
    }
    haltedUntil = null;
  }
}
~~~

- [ ] **Step 5: 让订单簿和撮合逐档执行价格笼子**

在 OrderBook 加入只跳过、绝不删除受保护档位的查询：

~~~java
public Optional<Order> bestExecutable(
    OrderSide side, java.util.function.Predicate<BigDecimal> executablePrice) {
  for (var level : levels(side).entrySet()) {
    if (!executablePrice.test(level.getKey())) continue;
    Optional<Order> first = level.getValue().values().stream().findFirst();
    if (first.isPresent()) return first;
  }
  return Optional.empty();
}
~~~

MatchingEngine 保留原构造器作为 ALLOW_ALL 测试便利入口，并增加实际生产构造器：

~~~java
private final java.util.function.Predicate<java.math.BigDecimal> executablePrice;

public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees,
                      LongSupplier matchSequence, Supplier<Instant> now,
                      Supplier<UUID> tradeIds) {
  this(book, rules, fees, matchSequence, now, tradeIds, price -> true);
}

public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees,
                      LongSupplier matchSequence, Supplier<Instant> now,
                      Supplier<UUID> tradeIds,
                      java.util.function.Predicate<java.math.BigDecimal> executablePrice) {
  this.book = book;
  this.rules = rules;
  this.fees = fees;
  this.matchSequence = matchSequence;
  this.now = now;
  this.tradeIds = tradeIds;
  this.executablePrice = executablePrice;
}
~~~

撮合循环把 book.best(opposite) 替换为：

~~~java
Order maker = book.bestExecutable(opposite, executablePrice).orElse(null);
~~~

生产装配传入 price -> limits.insideCage(price, tracker.referenceAt(clock.instant()))，所以每次实际成交前都会用最新参考价复查；参考价移动时，笼子外开放订单仍保留原 prioritySequence。

- [ ] **Step 6: 运行风险测试和完整回归**

Run: mvn -pl addon/exchange test

Expected: PASS；发现期参考价为 110.00、边界 120.00 可交易、120.01 被保护、受保护档位保留且不会成交、暂停时间分别为 2 和 10 分钟。

- [ ] **Step 7: 提交风险核心**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/risk addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingEngine.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/risk
git commit -m "feat(exchange): add price protection and circuit breakers"
~~~

### Task 7: 实现每市场串行执行和 requestId 幂等

**Files:**
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/ExchangeCommand.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/CommandResult.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/RequestResultStore.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/MarketCommandProcessor.java
- Create: addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service/MarketDispatcher.java
- Test: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/service/MarketDispatcherTest.java

- [ ] **Step 1: 写并发顺序和重复请求红灯测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDispatcherTest {
  @Test
  void serializesOneMarketAndReturnsFirstRequestResult() {
    AtomicInteger calls = new AtomicInteger();
    Map<UUID, CommandResult> results = new ConcurrentHashMap<>();
    RequestResultStore store = new RequestResultStore() {
      public Optional<CommandResult> find(UUID accountId, UUID requestId) {
        return Optional.ofNullable(results.get(requestId));
      }
      public CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result) {
        return results.putIfAbsent(requestId, result) == null ? result : results.get(requestId);
      }
    };
    MarketDispatcher dispatcher = new MarketDispatcher(store,
        command -> new CommandResult(command.requestId(), "accepted-" + calls.incrementAndGet()));
    UUID request = UUID.randomUUID();
    ExchangeCommand command = new ExchangeCommand("diamond-usd", UUID.randomUUID(), request, "PLACE");

    CommandResult first = dispatcher.submit(command).join();
    CommandResult duplicate = dispatcher.submit(command).join();

    assertThat(first).isEqualTo(duplicate);
    assertThat(calls).hasValue(1);
    dispatcher.close();
  }
}
~~~

- [ ] **Step 2: 运行并确认服务类型缺失**

Run: mvn -pl addon/exchange -Dtest=MarketDispatcherTest test

Expected: FAIL，编译器报告 MarketDispatcher 等类型不存在。

- [ ] **Step 3: 定义命令端口并实现市场专属单线程队列**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.UUID;

public record ExchangeCommand(String marketId, UUID accountId, UUID requestId, String operation) {
  public ExchangeCommand {
    if (marketId == null || accountId == null || requestId == null || operation == null) {
      throw new IllegalArgumentException("command identity is required");
    }
  }
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.UUID;

public record CommandResult(UUID requestId, String outcome) {}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.Optional;
import java.util.UUID;

public interface RequestResultStore {
  Optional<CommandResult> find(UUID accountId, UUID requestId);
  CommandResult putIfAbsent(UUID accountId, UUID requestId, CommandResult result);
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

@FunctionalInterface
public interface MarketCommandProcessor {
  CommandResult process(ExchangeCommand command);
}
~~~

~~~java
package com.ghostchu.quickshop.addon.exchange.core.service;

import java.util.Map;
import java.util.concurrent.*;

public final class MarketDispatcher implements AutoCloseable {
  private final RequestResultStore requestResults;
  private final MarketCommandProcessor processor;
  private final Map<String, ExecutorService> executors = new ConcurrentHashMap<>();

  public MarketDispatcher(RequestResultStore requestResults, MarketCommandProcessor processor) {
    this.requestResults = requestResults;
    this.processor = processor;
  }

  public CompletableFuture<CommandResult> submit(ExchangeCommand command) {
    ExecutorService executor = executors.computeIfAbsent(command.marketId(), market ->
        Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("qs-exchange-" + market + "-", 0).factory()));
    return CompletableFuture.supplyAsync(() ->
        requestResults.find(command.accountId(), command.requestId()).orElseGet(() -> {
          CommandResult result = processor.process(command);
          return requestResults.putIfAbsent(command.accountId(), command.requestId(), result);
        }), executor);
  }

  @Override
  public void close() {
    executors.values().forEach(ExecutorService::shutdown);
    executors.values().forEach(executor -> {
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
      } catch (InterruptedException interrupted) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    });
  }
}
~~~

- [ ] **Step 4: 运行并发和全量回归**

Run: mvn -pl addon/exchange test

Expected: PASS；同一 requestId 只调用一次 processor，关闭时队列在 10 秒内排空。

- [ ] **Step 5: 提交命令调度**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/service addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/service
git commit -m "feat(exchange): serialize market commands idempotently"
~~~

### Task 8: 加入资产性质与性能回归门槛

**Files:**
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/matching/MatchingConservationTest.java
- Create: addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBookPerformanceTest.java

- [ ] **Step 1: 写固定种子的随机资产守恒测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.TestFixtures;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.RepeatedTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingConservationTest {
  @RepeatedTest(20)
  void matchedQuantityNeverExceedsSubmittedQuantity() {
    Random random = new Random(0x515345L);
    OrderBook book = new OrderBook();
    AtomicLong priority = new AtomicLong();
    MatchingEngine engine = new MatchingEngine(book, TestFixtures.rules(), new FeeCalculator(2),
        priority::incrementAndGet, () -> Instant.EPOCH, UUID::randomUUID);
    long submittedBuy = 0;
    long submittedSell = 0;
    long traded = 0;
    for (int i = 0; i < 2_000; i++) {
      long quantity = random.nextLong(1, 101);
      OrderSide side = random.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
      if (side == OrderSide.BUY) submittedBuy += quantity;
      else submittedSell += quantity;
      Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
          side,
          OrderType.LIMIT, TimeInForce.GTC,
          BigDecimal.valueOf(random.nextLong(80, 121)).setScale(2), null,
          quantity, quantity, OrderStatus.OPEN, priority.incrementAndGet(),
          1, 1, Instant.EPOCH, Instant.EPOCH);
      MatchResult result = engine.submit(order);
      traded += result.trades().stream().mapToLong(Trade::quantity).sum();
      assertThat(result.trades()).allSatisfy(trade -> {
        assertThat(trade.quantity()).isPositive();
        assertThat(trade.makerFee()).isNotNegative();
        assertThat(trade.takerFee()).isNotNegative();
      });
    }
    long restingBuy = book.snapshot().stream().filter(order -> order.side() == OrderSide.BUY)
        .mapToLong(Order::remainingQuantity).sum();
    long restingSell = book.snapshot().stream().filter(order -> order.side() == OrderSide.SELL)
        .mapToLong(Order::remainingQuantity).sum();
    assertThat(traded + restingBuy).isLessThanOrEqualTo(submittedBuy);
    assertThat(traded + restingSell).isLessThanOrEqualTo(submittedSell);
  }
}
~~~

该测试需要在 OrderBook 添加只读快照，返回不可修改且按 bid 后 ask 排列的列表：

~~~java
public java.util.List<Order> snapshot() {
  java.util.ArrayList<Order> result = new java.util.ArrayList<>();
  bids.values().forEach(level -> result.addAll(level.values()));
  asks.values().forEach(level -> result.addAll(level.values()));
  return java.util.List.copyOf(result);
}
~~~

- [ ] **Step 2: 运行性质测试确认 snapshot 红灯**

Run: mvn -pl addon/exchange -Dtest=MatchingConservationTest test

Expected: FAIL，编译器报告 OrderBook.snapshot 不存在。

- [ ] **Step 3: 添加 snapshot 并重新运行逐侧守恒断言**

把 Step 1 已给出的 snapshot 方法加入 OrderBook；测试分别累计 submittedBuy、submittedSell、restingBuy、restingSell 和 traded，不能用买卖总和掩盖单侧超量。测试保持固定随机种子并运行 20 次。

- [ ] **Step 4: 写 100,000 开放订单基准测试**

~~~java
package com.ghostchu.quickshop.addon.exchange.core.book;

import com.ghostchu.quickshop.addon.exchange.core.model.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTimeout;

class OrderBookPerformanceTest {
  @Test
  @Tag("performance")
  void insertsAndCancelsOneHundredThousandOrdersWithinBaseline() {
    assertTimeout(Duration.ofSeconds(8), () -> {
      OrderBook book = new OrderBook();
      UUID[] ids = new UUID[100_000];
      for (int i = 0; i < ids.length; i++) {
        ids[i] = UUID.randomUUID();
        book.add(new Order(ids[i], UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
            i % 2 == 0 ? OrderSide.BUY : OrderSide.SELL,
            OrderType.LIMIT, TimeInForce.GTC,
            BigDecimal.valueOf(80 + (i % 41)).setScale(2), null,
            1, 1, OrderStatus.OPEN, i + 1L, 1, 1, Instant.EPOCH, Instant.EPOCH));
      }
      for (int i = 0; i < ids.length; i += 2) book.cancel(ids[i]);
    });
  }
}
~~~

- [ ] **Step 5: 运行完整测试与性能标签**

Run: mvn -pl addon/exchange test

Expected: BUILD SUCCESS；随机性质测试 20 次全部通过，100,000 订单测试在参考开发机 8 秒硬上限内完成。该 8 秒只防止算法退化；Phase 4 的端到端基准负责 P95 目标。

- [ ] **Step 6: 提交性质和性能测试**

~~~bash
git add addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core/book/OrderBook.java addon/exchange/src/test/java/com/ghostchu/quickshop/addon/exchange/core
git commit -m "test(exchange): guard matching conservation and performance"
~~~

## Phase 1 验收

Run: mvn -pl addon/exchange -am clean verify

Expected: BUILD SUCCESS；core 下没有 org.bukkit、io.papermc 或 net.tnemc import；限价、市价、部分成交、价格时间优先、Maker 价格、费用、滑点、自成交、参考价、价格笼子、熔断、幂等和队列关闭都有自动测试。

Run: rg -n "org\.bukkit|io\.papermc|net\.tnemc" addon/exchange/src/main/java/com/ghostchu/quickshop/addon/exchange/core

Expected: 无输出。
