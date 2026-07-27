package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchResult;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.matching.Reservation;
import com.ghostchu.quickshop.addon.exchange.core.matching.ReservationCalculator;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.TradePermission;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class PersistentOrderService {
  public static final UUID FEE_ACCOUNT_ID =
      UUID.nameUUIDFromBytes("quickshop-exchange-fees".getBytes(StandardCharsets.UTF_8));

  private static final String PLACE_OPERATION = "PLACE";
  private static final long REFERENCE_DISCOVERY_QUANTITY = 100;
  private static final Duration REFERENCE_WINDOW = Duration.ofMinutes(5);
  private static final Map<String, Object> MARKET_SERIAL_EXECUTORS = new ConcurrentHashMap<>();
  private final ExchangeRepository repository;
  private final MarketRules rules;
  private final RiskLimits riskLimits;
  private final FeeCalculator fees;
  private final ReservationCalculator reservations;
  private final TimeOrderedIdGenerator ids;
  private final Supplier<Instant> now;
  private final RecoveryHandler recovery;
  private volatile OrderBook committedBook = new OrderBook();
  private volatile ReferencePriceTracker referencePrices;
  private volatile CircuitBreaker circuitBreaker;
  private volatile long committedMarketVersion = Long.MIN_VALUE;

  /** Production wiring should prefer the constructor that supplies a recovery handler. */
  public PersistentOrderService(ExchangeRepository repository, MarketRules rules) {
    this(repository, rules, RiskLimits.defaults(), RecoveryHandler.NO_OP);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery) {
    this(repository, rules, riskLimits, recovery,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.riskLimits = Objects.requireNonNull(riskLimits, "riskLimits");
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.now = Objects.requireNonNull(now, "now");
    this.fees = new FeeCalculator(rules.priceScale());
    this.reservations = new ReservationCalculator(fees);
    this.referencePrices = new ReferencePriceTracker(
        rules.basePrice(), REFERENCE_DISCOVERY_QUANTITY, REFERENCE_WINDOW, rules.priceScale());
    this.circuitBreaker = new CircuitBreaker(riskLimits);
  }

  public OrderReceipt place(OrderRequest request) throws SQLException {
    validate(request);
    Object serialExecutor = MARKET_SERIAL_EXECUTORS.computeIfAbsent(
        request.marketId(), ignored -> new Object());
    synchronized (serialExecutor) {
      AtomicReference<TransactionOutcome> attemptedOutcome = new AtomicReference<>();
      try {
        TransactionOutcome outcome = repository.inTransaction(tx -> {
          TransactionOutcome settled = settle(tx, request);
          attemptedOutcome.set(settled);
          return settled;
        });
        publish(outcome);
        return outcome.receipt();
      } catch (SQLException failure) {
        OrderReceipt committed = committedReceipt(request, failure);
        if (committed != null) {
          TransactionOutcome attempted = attemptedOutcome.get();
          if (attempted != null && committed.equals(attempted.receipt())) {
            publish(attempted);
          }
          return committed;
        }
        enterRecovery(request.marketId(), failure);
        throw failure;
      }
    }
  }

  private void publish(TransactionOutcome outcome) {
    if (outcome.duplicate()) {
      return;
    }
    committedBook = outcome.book();
    referencePrices = outcome.referencePrices();
    circuitBreaker = outcome.circuitBreaker();
    committedMarketVersion = outcome.marketVersion();
  }

  private OrderReceipt committedReceipt(OrderRequest request, SQLException originalFailure) {
    try {
      StoredRequestResult stored = repository.inTransaction(tx ->
          tx.requestResult(request.accountId(), request.requestId()).orElse(null));
      if (stored == null) {
        return null;
      }
      if (!PLACE_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return decodeReceipt(stored.payload());
    } catch (SQLException lookupFailure) {
      originalFailure.addSuppressed(lookupFailure);
      return null;
    }
  }

  private TransactionOutcome settle(ExchangeTransaction tx, OrderRequest request)
      throws SQLException {
    MarketState beforeState = tx.marketState(request.marketId());
    StoredRequestResult stored = tx.requestResult(request.accountId(), request.requestId())
        .orElse(null);
    if (stored != null) {
      if (!PLACE_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return TransactionOutcome.duplicate(decodeReceipt(stored.payload()));
    }

    if (beforeState.status() != MarketStatus.OPEN) {
      throw new IllegalStateException(
          "market " + request.marketId() + " is " + beforeState.status());
    }
    RuntimeRiskSnapshot runtimeRisk = runtimeRisk(beforeState);
    List<PersistedOrder> persistedOrders = tx.openOrders(request.marketId());

    Instant createdAt = now.get();
    long prioritySequence = Math.addExact(beforeState.prioritySequence(), 1);
    Order incoming = createOrder(request, prioritySequence, createdAt);
    OrderBook transactionBook = new OrderBook();
    Map<UUID, PersistedOrder> persistedById = new HashMap<>();
    for (PersistedOrder persisted : persistedOrders) {
      transactionBook.add(persisted.order());
      persistedById.put(persisted.order().orderId(), persisted);
    }

    Reservation reservation = incoming.type() == OrderType.MARKET
        ? reservations.reserve(incoming, rules, transactionBook,
            price -> riskLimits.insideCage(price, beforeState.referencePrice()))
        : reservations.reserve(incoming, rules);

    AtomicLong matchSequence = new AtomicLong(beforeState.matchSequence());
    ReferencePriceTracker transactionPrices = runtimeRisk.referencePrices();
    CircuitBreaker transactionBreaker = runtimeRisk.circuitBreaker();
    MatchingEngine engine = new MatchingEngine(transactionBook, rules, fees,
        matchSequence::incrementAndGet, now, ids,
        price -> riskLimits.insideCage(price, beforeState.referencePrice()));
    MatchResult match = engine.submit(incoming);
    Order taker = match.selfTradeRejected()
        ? incoming.withStatus(OrderStatus.REJECTED, now.get()) : match.finalOrder();
    lockAssets(tx, incoming, match);
    freeze(tx, incoming, reservation);

    Map<UUID, BigDecimal> currencyReservations = new HashMap<>();
    Map<UUID, Long> itemReservations = new HashMap<>();
    for (PersistedOrder persisted : persistedOrders) {
      currencyReservations.put(persisted.order().orderId(), persisted.reservedCurrency());
      itemReservations.put(persisted.order().orderId(), persisted.reservedQuantity());
    }
    currencyReservations.put(incoming.orderId(), reservation.frozenCurrency());
    itemReservations.put(incoming.orderId(), reservation.frozenQuantity());

    for (Trade trade : match.trades()) {
      settleTrade(tx, incoming, trade, currencyReservations, itemReservations);
    }

    for (Order maker : match.changedMakers()) {
      releaseOpenBuyExcess(tx, maker, currencyReservations);
      releaseTerminalReservation(tx, maker, currencyReservations, itemReservations);
      PersistedOrder persisted = persistedById.get(maker.orderId());
      tx.updateOrder(maker, currencyReservations.get(maker.orderId()),
          itemReservations.get(maker.orderId()), persisted.version());
    }
    long reservedTakerItemsBeforeRelease = itemReservations.get(taker.orderId());
    BigDecimal takerCurrencyRelease = releaseOpenBuyExcess(
        tx, taker, currencyReservations).add(releaseTerminalReservation(
            tx, taker, currencyReservations, itemReservations));
    long takerItemRelease = taker.side() == OrderSide.SELL && isTerminal(taker)
        ? reservedTakerItemsBeforeRelease : 0;
    tx.insertOrder(taker, currencyReservations.get(taker.orderId()),
        itemReservations.get(taker.orderId()));

    for (Trade trade : match.trades()) {
      tx.insertTrade(trade);
      appendTradeJournals(tx, incoming, trade);
    }

    MarketState afterState = updateRiskState(
        tx, beforeState, prioritySequence, matchSequence.get(), match.trades(),
        transactionPrices, transactionBreaker);
    tx.updateMarketState(afterState, beforeState.version());

    SettlementPlan plan = new SettlementPlan(taker, match.changedMakers(), match.trades(),
        takerCurrencyRelease, takerItemRelease);
    OrderReceipt receipt = new OrderReceipt(
        request.requestId(), taker.orderId(), taker.status().name(), plan.trades());
    tx.putRequestResult(new StoredRequestResult(
        request.accountId(), request.requestId(), PLACE_OPERATION, encodeReceipt(receipt)));
    return TransactionOutcome.committed(
        receipt, plan, transactionBook, transactionPrices, transactionBreaker,
        afterState.version());
  }

  public void publishRecoveredState(
      OrderBook rebuiltBook, ReferencePriceTracker rebuiltReferencePrices,
      CircuitBreaker rebuiltCircuitBreaker, long marketVersion) {
    Objects.requireNonNull(rebuiltBook, "rebuiltBook");
    Objects.requireNonNull(rebuiltReferencePrices, "rebuiltReferencePrices");
    Objects.requireNonNull(rebuiltCircuitBreaker, "rebuiltCircuitBreaker");
    Object serialExecutor = MARKET_SERIAL_EXECUTORS.computeIfAbsent(
        rules.marketId(), ignored -> new Object());
    synchronized (serialExecutor) {
      committedBook = rebuiltBook;
      referencePrices = rebuiltReferencePrices.copy();
      circuitBreaker = rebuiltCircuitBreaker.copy();
      committedMarketVersion = marketVersion;
    }
  }

  private RuntimeRiskSnapshot runtimeRisk(MarketState state) {
    if (committedMarketVersion == state.version()) {
      return new RuntimeRiskSnapshot(referencePrices.copy(), circuitBreaker.copy());
    }
    return new RuntimeRiskSnapshot(
        ReferencePriceTracker.restored(state.referencePrice(), REFERENCE_DISCOVERY_QUANTITY,
            REFERENCE_WINDOW, rules.priceScale()),
        CircuitBreaker.restored(riskLimits, state.status(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil()));
  }

  private void validate(OrderRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.requestId() == null || request.accountId() == null
        || request.marketId() == null || request.marketId().isBlank() || request.side() == null
        || request.type() == null) {
      throw new IllegalArgumentException("order request identity is required");
    }
    if (!rules.marketId().equals(request.marketId())) {
      throw new IllegalArgumentException("order market does not match service");
    }
    rules.validateQuantity(request.quantity());
    OrderType type = parseType(request.type());
    if (type == OrderType.LIMIT) {
      rules.validatePrice(request.price());
      if (request.slippageBoundary() != null) {
        throw new IllegalArgumentException("limit order cannot have a slippage boundary");
      }
    } else {
      rules.validatePrice(request.slippageBoundary());
      if (request.price() != null) {
        throw new IllegalArgumentException("market order cannot have a limit price");
      }
    }
  }

  private Order createOrder(OrderRequest request, long prioritySequence, Instant createdAt) {
    OrderType type = parseType(request.type());
    return new Order(ids.get(), request.requestId(), request.marketId(), request.accountId(),
        request.side(), type, type == OrderType.LIMIT ? TimeInForce.GTC : TimeInForce.IOC,
        request.price(), request.slippageBoundary(), request.quantity(), request.quantity(),
        OrderStatus.OPEN, prioritySequence, 1, 1, createdAt, createdAt);
  }

  private static OrderType parseType(String type) {
    try {
      return OrderType.valueOf(type);
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("unsupported order type: " + type, failure);
    }
  }

  private void lockAssets(ExchangeTransaction tx, Order incoming, MatchResult match)
      throws SQLException {
    Set<LockKey> involved = new LinkedHashSet<>();
    involved.add(incoming.side() == OrderSide.BUY
        ? new LockKey(incoming.accountId(), rules.currencyId(), true)
        : new LockKey(incoming.accountId(), rules.marketId(), false));
    for (Trade trade : match.trades()) {
      involved.add(new LockKey(trade.buyerAccountId(), rules.currencyId(), true));
      involved.add(new LockKey(trade.buyerAccountId(), rules.marketId(), false));
      involved.add(new LockKey(trade.sellerAccountId(), rules.currencyId(), true));
      involved.add(new LockKey(trade.sellerAccountId(), rules.marketId(), false));
      if (trade.makerFee().add(trade.takerFee()).signum() > 0) {
        involved.add(new LockKey(FEE_ACCOUNT_ID, rules.currencyId(), true));
      }
    }
    ArrayList<LockKey> keys = new ArrayList<>(involved);
    keys.sort(Comparator.comparing((LockKey key) -> key.accountId().toString())
        .thenComparing(LockKey::assetId)
        .thenComparing(LockKey::currency));
    for (LockKey key : keys) {
      if (key.currency()) {
        tx.currency(key.accountId(), key.assetId());
      } else {
        tx.inventory(key.accountId(), key.assetId());
      }
    }
  }

  private void freeze(ExchangeTransaction tx, Order order, Reservation reservation)
      throws SQLException {
    if (order.side() == OrderSide.BUY && reservation.frozenCurrency().signum() > 0) {
      tx.freezeCurrency(order.accountId(), rules.currencyId(), reservation.frozenCurrency());
    } else if (order.side() == OrderSide.SELL && reservation.frozenQuantity() > 0) {
      tx.freezeItems(order.accountId(), rules.marketId(), reservation.frozenQuantity());
    }
  }

  private void settleTrade(ExchangeTransaction tx, Order incoming, Trade trade,
                           Map<UUID, BigDecimal> currencyReservations,
                           Map<UUID, Long> itemReservations) throws SQLException {
    boolean takerBuys = incoming.side() == OrderSide.BUY;
    BigDecimal buyerFee = takerBuys ? trade.takerFee() : trade.makerFee();
    BigDecimal sellerFee = takerBuys ? trade.makerFee() : trade.takerFee();
    BigDecimal notional = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));
    BigDecimal buyerConsumption = notional.add(buyerFee);
    UUID buyerOrder = takerBuys ? incoming.orderId() : trade.makerOrderId();
    UUID sellerOrder = takerBuys ? trade.makerOrderId() : incoming.orderId();

    tx.consumeFrozenCurrency(trade.buyerAccountId(), rules.currencyId(), buyerConsumption);
    tx.creditAvailableItems(trade.buyerAccountId(), rules.marketId(), trade.quantity());
    tx.consumeFrozenItems(trade.sellerAccountId(), rules.marketId(), trade.quantity());
    BigDecimal sellerCredit = notional.subtract(sellerFee);
    if (sellerCredit.signum() > 0) {
      tx.creditAvailableCurrency(trade.sellerAccountId(), rules.currencyId(), sellerCredit);
    }
    BigDecimal feeCredit = buyerFee.add(sellerFee);
    if (feeCredit.signum() > 0) {
      tx.creditAvailableCurrency(FEE_ACCOUNT_ID, rules.currencyId(), feeCredit);
    }

    currencyReservations.compute(buyerOrder,
        (ignored, reserved) -> reserved.subtract(buyerConsumption));
    itemReservations.compute(sellerOrder,
        (ignored, reserved) -> Math.subtractExact(reserved, trade.quantity()));
  }

  private BigDecimal releaseTerminalReservation(
      ExchangeTransaction tx, Order order, Map<UUID, BigDecimal> currencyReservations,
      Map<UUID, Long> itemReservations) throws SQLException {
    if (!isTerminal(order)) {
      return BigDecimal.ZERO;
    }
    if (order.side() == OrderSide.BUY) {
      BigDecimal release = currencyReservations.get(order.orderId());
      if (release.signum() > 0) {
        tx.releaseCurrency(order.accountId(), rules.currencyId(), release);
        currencyReservations.put(order.orderId(), BigDecimal.ZERO);
      }
      return release;
    }
    long release = itemReservations.get(order.orderId());
    if (release > 0) {
      tx.releaseItems(order.accountId(), rules.marketId(), release);
      itemReservations.put(order.orderId(), 0L);
    }
    return BigDecimal.ZERO;
  }

  private BigDecimal releaseOpenBuyExcess(
      ExchangeTransaction tx, Order order, Map<UUID, BigDecimal> currencyReservations)
      throws SQLException {
    if (order.side() != OrderSide.BUY || order.type() != OrderType.LIMIT || isTerminal(order)) {
      return BigDecimal.ZERO;
    }
    BigDecimal reserved = currencyReservations.get(order.orderId());
    BigDecimal required = reservations.reserve(order, rules).frozenCurrency();
    BigDecimal release = reserved.subtract(required);
    if (release.signum() < 0) {
      throw new IllegalStateException("remaining buy reservation is underfunded");
    }
    if (release.signum() > 0) {
      tx.releaseCurrency(order.accountId(), rules.currencyId(), release);
      currencyReservations.put(order.orderId(), required);
    }
    return release;
  }

  private static boolean isTerminal(Order order) {
    return order.status() == OrderStatus.FILLED || order.status() == OrderStatus.CANCELLED
        || order.status() == OrderStatus.REJECTED;
  }

  private void appendTradeJournals(ExchangeTransaction tx, Order incoming, Trade trade)
      throws SQLException {
    boolean makerBuys = incoming.side() == OrderSide.SELL;
    BigDecimal buyerFee = makerBuys ? trade.makerFee() : trade.takerFee();
    BigDecimal sellerFee = makerBuys ? trade.takerFee() : trade.makerFee();
    BigDecimal notional = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));
    BigDecimal buyerDebit = notional.add(buyerFee).negate();
    BigDecimal sellerCredit = notional.subtract(sellerFee);
    BigDecimal feeCredit = buyerFee.add(sellerFee);
    Instant at = trade.executedAt();
    tx.appendJournal(new LedgerJournal(ids.get(), "TRADE_CURRENCY", trade.tradeId(), at, null,
        List.of(
            entry("liability:currency:" + trade.buyerAccountId(), rules.currencyId(), buyerDebit, at),
            entry("liability:currency:" + trade.sellerAccountId(), rules.currencyId(), sellerCredit, at),
            entry("liability:fee:" + FEE_ACCOUNT_ID, rules.currencyId(), feeCredit, at),
            entry("custody:currency:" + rules.currencyId(), rules.currencyId(), BigDecimal.ZERO, at))));
    BigDecimal quantity = BigDecimal.valueOf(trade.quantity());
    tx.appendJournal(new LedgerJournal(ids.get(), "TRADE_ITEM", trade.tradeId(), at, null,
        List.of(
            entry("liability:item:" + trade.sellerAccountId(), rules.marketId(), quantity.negate(), at),
            entry("liability:item:" + trade.buyerAccountId(), rules.marketId(), quantity, at),
            entry("custody:item:" + rules.marketId(), rules.marketId(), BigDecimal.ZERO, at))));
  }

  private LedgerEntry entry(String account, String asset, BigDecimal amount, Instant at) {
    return new LedgerEntry(ids.get(), account, asset, amount, at);
  }

  private MarketState updateRiskState(
      ExchangeTransaction tx, MarketState before, long prioritySequence, long matchSequence,
      List<Trade> trades, ReferencePriceTracker prices, CircuitBreaker breaker)
      throws SQLException {
    MarketStatus status = before.status();
    BigDecimal reference = before.referencePrice();
    BigDecimal lastPrice = before.lastPrice();
    Instant haltedUntil = before.haltedUntil();
    for (Trade trade : trades) {
      BigDecimal preTradeReference = reference;
      TradePermission permission = breaker.onPrice(trade.price(), preTradeReference, trade.executedAt());
      prices.record(trade.price(), trade.quantity(), trade.executedAt());
      lastPrice = trade.price();
      if (permission.allowed()) {
        reference = prices.referenceAt(trade.executedAt());
      } else {
        status = MarketStatus.HALTED;
        haltedUntil = permission.haltUntil().orElseThrow();
        reference = preTradeReference;
        if (permission.level() == 2) {
          tx.insertHighAlert(ids.get(), rules.marketId(), "CIRCUIT_BREAKER_LEVEL_2",
              encodeLevelTwoAlert(reference, trade.price()),
              trade.executedAt());
        }
      }
    }
    return new MarketState(before.marketId(), status, prioritySequence, matchSequence,
        reference, lastPrice, haltedUntil, before.version() + 1);
  }

  private void enterRecovery(String marketId, SQLException failure) {
    try {
      repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        tx.updateMarketState(new MarketState(state.marketId(), MarketStatus.RECOVERING,
            state.prioritySequence(), state.matchSequence(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil(), state.version() + 1), state.version());
        return null;
      });
    } catch (SQLException | RuntimeException recoveryWriteFailure) {
      failure.addSuppressed(recoveryWriteFailure);
    }
    try {
      recovery.recover(marketId, failure);
    } catch (RuntimeException recoveryFailure) {
      failure.addSuppressed(recoveryFailure);
    }
  }

  private static String encodeReceipt(OrderReceipt receipt) {
    JsonObject json = new JsonObject();
    json.addProperty("requestId", receipt.requestId().toString());
    json.addProperty("orderId", receipt.orderId().toString());
    json.addProperty("status", receipt.status());
    JsonArray trades = new JsonArray();
    for (Trade trade : receipt.trades()) {
      JsonObject encoded = new JsonObject();
      encoded.addProperty("tradeId", trade.tradeId().toString());
      encoded.addProperty("marketId", trade.marketId());
      encoded.addProperty("makerOrderId", trade.makerOrderId().toString());
      encoded.addProperty("takerOrderId", trade.takerOrderId().toString());
      encoded.addProperty("buyerAccountId", trade.buyerAccountId().toString());
      encoded.addProperty("sellerAccountId", trade.sellerAccountId().toString());
      encoded.addProperty("price", trade.price().toPlainString());
      encoded.addProperty("quantity", trade.quantity());
      encoded.addProperty("makerFee", trade.makerFee().toPlainString());
      encoded.addProperty("takerFee", trade.takerFee().toPlainString());
      encoded.addProperty("matchSequence", trade.matchSequence());
      encoded.addProperty("executedAt", trade.executedAt().toString());
      trades.add(encoded);
    }
    json.add("trades", trades);
    return json.toString();
  }

  private static OrderReceipt decodeReceipt(String payload) throws SQLException {
    try {
      JsonObject receipt = JsonParser.parseString(payload).getAsJsonObject();
      ArrayList<Trade> trades = new ArrayList<>();
      for (JsonElement element : receipt.getAsJsonArray("trades")) {
        JsonObject trade = element.getAsJsonObject();
        trades.add(new Trade(uuid(trade, "tradeId"), string(trade, "marketId"),
            uuid(trade, "makerOrderId"), uuid(trade, "takerOrderId"),
            uuid(trade, "buyerAccountId"), uuid(trade, "sellerAccountId"),
            decimal(trade, "price"), trade.get("quantity").getAsLong(),
            decimal(trade, "makerFee"), decimal(trade, "takerFee"),
            trade.get("matchSequence").getAsLong(),
            Instant.parse(string(trade, "executedAt"))));
      }
      return new OrderReceipt(uuid(receipt, "requestId"), uuid(receipt, "orderId"),
          string(receipt, "status"), trades);
    } catch (RuntimeException failure) {
      throw new SQLException("invalid stored order receipt", failure);
    }
  }

  private static String encodeLevelTwoAlert(BigDecimal reference, BigDecimal tradePrice) {
    JsonObject alert = new JsonObject();
    alert.addProperty("level", 2);
    alert.addProperty("referencePrice", reference.toPlainString());
    alert.addProperty("tradePrice", tradePrice.toPlainString());
    return alert.toString();
  }

  private static UUID uuid(JsonObject json, String field) {
    return UUID.fromString(string(json, field));
  }

  private static BigDecimal decimal(JsonObject json, String field) {
    return new BigDecimal(string(json, field));
  }

  private static String string(JsonObject json, String field) {
    JsonElement value = json.get(field);
    if (value == null || value.isJsonNull()) {
      throw new IllegalArgumentException("missing JSON field: " + field);
    }
    return value.getAsString();
  }

  private record LockKey(UUID accountId, String assetId, boolean currency) {}

  private record RuntimeRiskSnapshot(
      ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker) {}

  private record TransactionOutcome(
      OrderReceipt receipt, SettlementPlan plan, OrderBook book,
      ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker,
      long marketVersion, boolean duplicate) {
    private static TransactionOutcome duplicate(OrderReceipt receipt) {
      return new TransactionOutcome(receipt, null, null, null, null, Long.MIN_VALUE, true);
    }

    private static TransactionOutcome committed(
        OrderReceipt receipt, SettlementPlan plan, OrderBook book,
        ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker,
        long marketVersion) {
      return new TransactionOutcome(
          receipt, plan, book, referencePrices, circuitBreaker, marketVersion, false);
    }
  }
}
