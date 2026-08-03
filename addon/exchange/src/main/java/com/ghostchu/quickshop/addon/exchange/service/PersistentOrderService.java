package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.matching.FeeCalculator;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchResult;
import com.ghostchu.quickshop.addon.exchange.core.matching.MatchingEngine;
import com.ghostchu.quickshop.addon.exchange.core.matching.Reservation;
import com.ghostchu.quickshop.addon.exchange.core.matching.ReservationCalculator;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeOrderedIdGenerator;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountOrderLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.AccountRiskSnapshot;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.OrderRateLimiter;
import com.ghostchu.quickshop.addon.exchange.core.risk.OrderRiskService;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.TradePermission;
import com.ghostchu.quickshop.addon.exchange.core.trust.BehaviorRiskAction;
import com.ghostchu.quickshop.addon.exchange.core.trust.BehaviorRiskDecision;
import com.ghostchu.quickshop.addon.exchange.core.trust.BehaviorRiskEvaluator;
import com.ghostchu.quickshop.addon.exchange.core.trust.BehaviorRiskPolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityClassifier;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceEngine;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.repository.MarketFeeSchedule;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.function.BiPredicate;
import java.util.function.Supplier;

public final class PersistentOrderService {
  public static final UUID FEE_ACCOUNT_ID =
      UUID.nameUUIDFromBytes("quickshop-exchange-fees".getBytes(StandardCharsets.UTF_8));

  private static final String PLACE_OPERATION = "PLACE";
  private static final String FORCE_CANCEL_OPERATION = "FORCE_CANCEL";
  private static final long REFERENCE_DISCOVERY_QUANTITY = 100;
  private static final Duration REFERENCE_WINDOW = Duration.ofMinutes(5);
  private static final Map<MarketCoordinationKey, MarketRuntimeState> MARKET_RUNTIMES =
      new ConcurrentHashMap<>();
  private final ExchangeRepository repository;
  private final MarketRules rules;
  private final RiskLimits riskLimits;
  private final AccountOrderLimits accountLimits;
  private final OrderRiskService orderRisks;
  private final FeeCalculator fees;
  private final ReservationCalculator reservations;
  private final TimeOrderedIdGenerator ids;
  private final Supplier<Instant> now;
  private final RecoveryHandler recovery;
  private final SettlementObserver observer;
  private final MarketDataService marketData;
  private final OrderBookRecoveryService marketRecovery;
  private final long discoveryQuantity;
  private final TrustedPricePolicy trustedPolicy;
  private final LiquidityClassifier liquidityClassifier;
  private final TrustedPriceEngine trustedPriceEngine;
  private final BehaviorRiskEvaluator behaviorRiskEvaluator;
  private final MarketRuntimeState runtimeState;

  public MarketRules marketRules() {
    return rules;
  }

  /** Production wiring should prefer the constructor that supplies a recovery handler. */
  public PersistentOrderService(ExchangeRepository repository, MarketRules rules) {
    this(repository, rules, RiskLimits.defaults(), RecoveryHandler.NO_OP);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                SettlementObserver observer) {
    this(repository, rules, riskLimits, recovery, observer,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults());
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        AccountOrderLimits.defaults(), marketData);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                AccountOrderLimits accountLimits, MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, accountLimits, marketData,
        REFERENCE_DISCOVERY_QUANTITY);
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                AccountOrderLimits accountLimits, MarketDataService marketData,
                                long discoveryQuantity) {
    this(repository, rules, riskLimits, recovery, accountLimits, marketData,
        discoveryQuantity, TrustedPricePolicy.defaults());
  }

  public PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                                RiskLimits riskLimits, RecoveryHandler recovery,
                                AccountOrderLimits accountLimits, MarketDataService marketData,
                                long discoveryQuantity, TrustedPricePolicy trustedPolicy) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        accountLimits, marketData, discoveryQuantity, trustedPolicy);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         AccountOrderLimits accountLimits) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE,
        new TimeOrderedIdGenerator(System::currentTimeMillis, new java.util.Random()), Instant::now,
        accountLimits);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now) {
    this(repository, rules, riskLimits, recovery, SettlementObserver.NONE, ids, now,
        AccountOrderLimits.defaults());
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now) {
    this(repository, rules, riskLimits, recovery, observer, ids, now,
        AccountOrderLimits.defaults());
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits) {
    this(repository, rules, riskLimits, recovery, observer, ids, now, accountLimits, null,
        REFERENCE_DISCOVERY_QUANTITY);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits, MarketDataService marketData) {
    this(repository, rules, riskLimits, recovery, observer, ids, now, accountLimits, marketData,
        REFERENCE_DISCOVERY_QUANTITY);
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits, MarketDataService marketData,
                         long discoveryQuantity) {
    this(repository, rules, riskLimits, recovery, observer, ids, now, accountLimits, marketData,
        discoveryQuantity, TrustedPricePolicy.defaults());
  }

  PersistentOrderService(ExchangeRepository repository, MarketRules rules,
                         RiskLimits riskLimits, RecoveryHandler recovery,
                         SettlementObserver observer,
                         TimeOrderedIdGenerator ids, Supplier<Instant> now,
                         AccountOrderLimits accountLimits, MarketDataService marketData,
                         long discoveryQuantity, TrustedPricePolicy trustedPolicy) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.riskLimits = Objects.requireNonNull(riskLimits, "riskLimits");
    this.accountLimits = Objects.requireNonNull(accountLimits, "accountLimits");
    this.orderRisks = new OrderRiskService(new OrderRateLimiter(
        accountLimits.operationsPerSecond(), accountLimits.operationsPerMinute()));
    this.recovery = Objects.requireNonNull(recovery, "recovery");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.marketData = marketData;
    if (discoveryQuantity <= 0) {
      throw new IllegalArgumentException("trusted discovery quantity must be positive");
    }
    this.discoveryQuantity = discoveryQuantity;
    this.trustedPolicy = Objects.requireNonNull(trustedPolicy, "trustedPolicy");
    this.liquidityClassifier = new LiquidityClassifier(trustedPolicy);
    this.trustedPriceEngine = new TrustedPriceEngine();
    this.behaviorRiskEvaluator = new BehaviorRiskEvaluator(BehaviorRiskPolicy.defaults());
    this.ids = Objects.requireNonNull(ids, "ids");
    this.now = Objects.requireNonNull(now, "now");
    this.fees = new FeeCalculator(rules.priceScale());
    this.reservations = new ReservationCalculator(fees);
    this.marketRecovery = new OrderBookRecoveryService(
        repository, rules, riskLimits, discoveryQuantity, trustedPolicy);
    MarketCoordinationKey coordinationKey = new MarketCoordinationKey(
        Objects.requireNonNull(repository.coordinationKey(), "repository coordination key"),
        rules.marketId());
    this.runtimeState = MARKET_RUNTIMES.computeIfAbsent(coordinationKey, ignored ->
        new MarketRuntimeState(
            new OrderBook(),
            new ReferencePriceTracker(rules.basePrice(), discoveryQuantity,
                REFERENCE_WINDOW, rules.priceScale()),
            new TrustedPriceState(rules.marketId(), rules.basePrice(), rules.basePrice(),
                Instant.EPOCH, LiquidityTier.LOW, 1, 0, 0),
            List.of(),
            new CircuitBreaker(riskLimits),
            Long.MIN_VALUE));
  }

  public OrderReceipt place(OrderRequest request) throws SQLException {
    validate(request);
    OrderReceipt stored = preflightRisk(request);
    if (stored != null) {
      return stored;
    }
    synchronized (runtimeState) {
      AtomicReference<TransactionOutcome> attemptedOutcome = new AtomicReference<>();
      try {
        TransactionOutcome outcome = repository.inTransaction(tx -> {
          TransactionOutcome settled = settle(tx, request);
          attemptedOutcome.set(settled);
          return settled;
        });
        publish(outcome);
        return outcome.receipt();
      } catch (SettlementObservationFailure failure) {
        RuntimeException injected = failure.original();
        for (Throwable suppressed : failure.getSuppressed()) {
          injected.addSuppressed(suppressed);
        }
        enterRecovery(request.marketId(), injected);
        throw injected;
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

  /** Cancels an active order under the same market serialization as matching. */
  public OrderReceipt forceCancel(UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    String normalizedReason = normalizeAdminReason(reason);
    synchronized (runtimeState) {
      AtomicReference<ForceCancelOutcome> attempted = new AtomicReference<>();
      try {
        ForceCancelOutcome outcome = repository.inTransaction(tx -> {
          ForceCancelOutcome cancelled = cancelOpenOrder(
              tx, actorId, requestId, orderId, normalizedReason);
          attempted.set(cancelled);
          return cancelled;
        });
        if (!outcome.duplicate()) {
          runtimeState.committedBook = outcome.book();
        }
        return outcome.receipt();
      } catch (SQLException failure) {
        OrderReceipt committed = committedForceCancelReceipt(actorId, requestId, failure);
        if (committed != null) {
          ForceCancelOutcome attemptedOutcome = attempted.get();
          if (attemptedOutcome != null && committed.equals(attemptedOutcome.receipt())) {
            runtimeState.committedBook = attemptedOutcome.book();
          } else {
            recoverFromDatabase();
          }
          return committed;
        }
        throw failure;
      }
    }
  }

  /** Cancels an order only when it belongs to the requesting account. */
  public OrderReceipt cancel(UUID accountId, UUID requestId, UUID orderId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orderId, "orderId");
    synchronized (runtimeState) {
      List<PersistedOrder> open = repository.inTransaction(tx -> tx.openOrders(rules.marketId()));
      PersistedOrder order = open.stream().filter(candidate ->
          candidate.order().orderId().equals(orderId)).findFirst()
          .orElseThrow(() -> new IllegalArgumentException("order is not open: " + orderId));
      if (!order.order().accountId().equals(accountId)) {
        throw new IllegalArgumentException("order is not owned by account");
      }
      return forceCancel(accountId, requestId, orderId, "player cancellation");
    }
  }

  private void publish(TransactionOutcome outcome) {
    if (outcome.duplicate()) {
      return;
    }
    runtimeState.committedBook = outcome.book();
    runtimeState.referencePrices = outcome.referencePrices();
    runtimeState.trustedPriceState = outcome.trustedPriceState();
    runtimeState.recentInfluences = outcome.recentInfluences();
    runtimeState.circuitBreaker = outcome.circuitBreaker();
    runtimeState.committedMarketVersion = outcome.marketVersion();
    if (marketData != null) {
      for (Trade trade : outcome.plan().trades()) {
        try {
          marketData.acceptCommitted(trade.marketId(), trade.price(), trade.quantity(),
              trade.executedAt());
        } catch (RuntimeException ignored) {
          // Market data must never turn an already committed order into a failed request.
        }
      }
    }
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

  private OrderReceipt committedForceCancelReceipt(
      UUID actorId, UUID requestId, SQLException originalFailure) {
    try {
      StoredRequestResult stored = repository.findRequestResult(actorId, requestId).orElse(null);
      if (stored == null) {
        return null;
      }
      if (!FORCE_CANCEL_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return decodeReceipt(stored.payload());
    } catch (SQLException lookupFailure) {
      originalFailure.addSuppressed(lookupFailure);
      return null;
    }
  }

  private OrderReceipt storedReceipt(OrderRequest request) throws SQLException {
    StoredRequestResult stored = repository.findRequestResult(request.accountId(), request.requestId())
        .orElse(null);
    if (stored == null) {
      return null;
    }
    if (!PLACE_OPERATION.equals(stored.operation())) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    return decodeReceipt(stored.payload());
  }

  private TransactionOutcome settle(ExchangeTransaction tx, OrderRequest request)
      throws SQLException {
    MarketState lockedState = tx.marketState(request.marketId());
    StoredRequestResult stored = tx.requestResult(request.accountId(), request.requestId())
        .orElse(null);
    if (stored != null) {
      if (!PLACE_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return TransactionOutcome.duplicate(decodeReceipt(stored.payload()));
    }

    if (lockedState.status() != MarketStatus.OPEN) {
      reject(OrderRiskService.RejectReason.MARKET_NOT_OPEN);
    }
    Instant evaluatedAt = now.get();
    RuntimeRiskSnapshot runtimeRisk = runtimeRisk(tx, lockedState, evaluatedAt);
    MarketState beforeState = runtimeRisk.state();
    BigDecimal trustedReference = runtimeRisk.trustedPriceState().trustedPrice();
    if (parseType(request.type()) == OrderType.MARKET) {
      OrderRiskService.RejectReason rejection = orderRisks.checkMarketSlippage(
          request.slippageBoundary(), trustedReference, riskLimits.maximumSlippage());
      if (rejection != null) {
        throw new IllegalStateException(rejection.name());
      }
    }
    List<PersistedOrder> persistedOrders = tx.openOrders(request.marketId());
    long structuralVersion = tx.marketStructuralVersion(request.marketId());
    MarketFeeSchedule feeSchedule = tx.marketFeeSchedule(request.marketId());
    if (feeSchedule.currencyScale() != rules.priceScale()) {
      throw new IllegalStateException("fee schedule currency scale does not match market rules");
    }

    Instant createdAt = now.get();
    long prioritySequence = Math.addExact(beforeState.prioritySequence(), 1);
    Order incoming = createOrder(
        request, prioritySequence, structuralVersion, feeSchedule.activeVersion(), createdAt);
    MarketRules incomingRules = rulesWithFees(feeSchedule.activeRates());
    OrderBook transactionBook = new OrderBook();
    Map<UUID, PersistedOrder> persistedById = new HashMap<>();
    for (PersistedOrder persisted : persistedOrders) {
      transactionBook.add(persisted.order());
      persistedById.put(persisted.order().orderId(), persisted);
    }

    if (incoming.type() == OrderType.LIMIT
        && !riskLimits.insideCage(incoming.limitPrice(), trustedReference)) {
      reject(OrderRiskService.RejectReason.PRICE_OUTSIDE_CAGE);
    }
    if (wouldSelfTrade(request, transactionBook, trustedReference)) {
      reject(OrderRiskService.RejectReason.SELF_TRADE);
    }

    AtomicLong matchSequence = new AtomicLong(beforeState.matchSequence());
    ReferencePriceTracker transactionPrices = runtimeRisk.referencePrices();
    TrustedPriceState transactionTrusted = runtimeRisk.trustedPriceState();
    ArrayList<TradeInfluence> transactionInfluences =
        new ArrayList<>(runtimeRisk.recentInfluences());
    CircuitBreaker transactionBreaker = runtimeRisk.circuitBreaker();
    AtomicReference<TrustedPriceState> plannedTrusted =
        new AtomicReference<>(transactionTrusted);
    ArrayList<TradeInfluence> plannedInfluences =
        new ArrayList<>(transactionInfluences);
    BiPredicate<Order, Order> executablePair = (takerOrder, makerOrder) ->
        behaviorRiskEvaluator.evaluate(
            rules.marketId(),
            TradeInfluence.pairKey(takerOrder.accountId(), makerOrder.accountId()),
            plannedTrusted.get().liquidityTier(), plannedInfluences, now.get()).action()
            != BehaviorRiskAction.PAIR_COOLDOWN;
    MatchingEngine engine = new MatchingEngine(transactionBook, rules, fees,
        matchSequence::incrementAndGet, now, ids,
        price -> riskLimits.insideCage(price, trustedReference),
        order -> feeSchedule.rates(order.feeVersion()), executablePair,
        trade -> advancePlannedRisk(trade, plannedTrusted, plannedInfluences));
    MatchResult match = engine.submit(incoming);
    if (match.selfTradeRejected()) {
      reject(OrderRiskService.RejectReason.SELF_TRADE);
    }
    Reservation reservation = incoming.type() == OrderType.MARKET
        ? reservations.reserve(incoming, incomingRules, match.trades())
        : reservations.reserve(incoming, incomingRules);

    long holding = tx.existingInventory(request.accountId(), rules.marketId())
        .map(balance -> balance.availableQuantity() + balance.frozenQuantity()).orElse(0L);
    BigDecimal frozenCurrency = tx.existingCurrency(request.accountId(), rules.currencyId())
        .map(balance -> balance.frozen()).orElse(BigDecimal.ZERO);
    int openOrders = (int) persistedOrders.stream()
        .filter(persisted -> persisted.order().accountId().equals(request.accountId()))
        .count();
    AccountRiskSnapshot accountRisk = new AccountRiskSnapshot(
        holding, frozenCurrency, openOrders);
    OrderRiskService.RejectReason exposureRejection = orderRisks.checkExposure(
        incoming.side() == OrderSide.BUY ? incoming.originalQuantity() : 0,
        incoming.side() == OrderSide.BUY ? reservation.frozenCurrency() : BigDecimal.ZERO,
        accountRisk, accountLimits, incoming.type() == OrderType.LIMIT);
    if (exposureRejection != null) {
      throw new IllegalStateException(exposureRejection.name());
    }
    Order taker = match.finalOrder();
    lockAssets(tx, incoming, match);
    freeze(tx, incoming, reservation);
    reached(SettlementStage.AFTER_RESERVATION);

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
      releaseOpenBuyExcess(tx, maker, currencyReservations, feeSchedule);
      releaseTerminalReservation(tx, maker, currencyReservations, itemReservations);
    }
    long reservedTakerItemsBeforeRelease = itemReservations.get(taker.orderId());
    BigDecimal takerCurrencyRelease = releaseOpenBuyExcess(
        tx, taker, currencyReservations, feeSchedule).add(releaseTerminalReservation(
            tx, taker, currencyReservations, itemReservations));
    long takerItemRelease = taker.side() == OrderSide.SELL && isTerminal(taker)
        ? reservedTakerItemsBeforeRelease : 0;
    reached(SettlementStage.AFTER_BALANCE_UPDATE);

    for (Order maker : match.changedMakers()) {
      PersistedOrder persisted = persistedById.get(maker.orderId());
      tx.updateOrder(maker, currencyReservations.get(maker.orderId()),
          itemReservations.get(maker.orderId()), persisted.version());
    }
    reached(SettlementStage.AFTER_MAKER_UPDATE);
    tx.insertOrder(taker, currencyReservations.get(taker.orderId()),
        itemReservations.get(taker.orderId()));
    reached(SettlementStage.AFTER_ORDER_INSERT);

    for (Trade trade : match.trades()) {
      tx.insertTrade(trade);
    }
    reached(SettlementStage.AFTER_TRADE_INSERT);
    for (Trade trade : match.trades()) {
      appendTradeJournals(tx, incoming, trade);
    }
    reached(SettlementStage.AFTER_LEDGER_INSERT);

    RiskUpdate riskUpdate = updateRiskState(
        tx, beforeState, prioritySequence, matchSequence.get(), match.trades(),
        transactionPrices, transactionTrusted, transactionInfluences, transactionBreaker);
    MarketState afterState = riskUpdate.state();
    tx.updateMarketState(afterState, beforeState.version());
    reached(SettlementStage.AFTER_RISK_UPDATE);

    SettlementPlan plan = new SettlementPlan(taker, match.changedMakers(), match.trades(),
        takerCurrencyRelease, takerItemRelease);
    OrderReceipt receipt = new OrderReceipt(
        request.requestId(), taker.orderId(), taker.status().name(), plan.trades());
    tx.putRequestResult(new StoredRequestResult(
        request.accountId(), request.requestId(), PLACE_OPERATION, encodeReceipt(receipt)));
    reached(SettlementStage.AFTER_REQUEST_RESULT);
    return TransactionOutcome.committed(
        receipt, plan, transactionBook, transactionPrices, riskUpdate.trustedPriceState(),
        riskUpdate.recentInfluences(), transactionBreaker, afterState.version());
  }

  private ForceCancelOutcome cancelOpenOrder(
      ExchangeTransaction tx, UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
    if (stored != null) {
      if (!FORCE_CANCEL_OPERATION.equals(stored.operation())) {
        throw new IllegalStateException("request id belongs to another operation");
      }
      return ForceCancelOutcome.duplicate(decodeReceipt(stored.payload()));
    }
    List<PersistedOrder> persistedOrders = tx.openOrders(rules.marketId());
    PersistedOrder persisted = persistedOrders.stream()
        .filter(candidate -> candidate.order().orderId().equals(orderId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("order is not open: " + orderId));
    Order before = persisted.order();
    Order cancelled = before.withStatus(OrderStatus.CANCELLED, now.get());
    if (before.side() == OrderSide.BUY && persisted.reservedCurrency().signum() > 0) {
      tx.releaseCurrency(before.accountId(), rules.currencyId(), persisted.reservedCurrency());
    } else if (before.side() == OrderSide.SELL && persisted.reservedQuantity() > 0) {
      tx.releaseItems(before.accountId(), rules.marketId(), persisted.reservedQuantity());
    }
    tx.updateOrder(cancelled, BigDecimal.ZERO, 0L, persisted.version());
    tx.appendAudit(new AuditRecord(ids.get(), actorId, "FORCE_CANCEL_ORDER", orderId.toString(),
        reason, orderState(before, persisted), orderState(cancelled,
            BigDecimal.ZERO, 0L), now.get()));
    OrderReceipt receipt = new OrderReceipt(requestId, orderId, cancelled.status().name(), List.of());
    tx.putRequestResult(new StoredRequestResult(
        actorId, requestId, FORCE_CANCEL_OPERATION, encodeReceipt(receipt)));
    OrderBook book = new OrderBook();
    for (PersistedOrder active : persistedOrders) {
      if (!active.order().orderId().equals(orderId)) {
        book.add(active.order());
      }
    }
    return ForceCancelOutcome.committed(receipt, book);
  }

  private static String normalizeAdminReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException("administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }

  private static String orderState(Order order, PersistedOrder persisted) {
    return orderState(order, persisted.reservedCurrency(), persisted.reservedQuantity());
  }

  private static String orderState(Order order, BigDecimal reservedCurrency, long reservedQuantity) {
    return "status=" + order.status() + ",remainingQuantity=" + order.remainingQuantity()
        + ",reservedCurrency=" + reservedCurrency.toPlainString()
        + ",reservedQuantity=" + reservedQuantity;
  }

  public void publishRecoveredState(
      OrderBook rebuiltBook, ReferencePriceTracker rebuiltReferencePrices,
      CircuitBreaker rebuiltCircuitBreaker, long marketVersion) {
    Objects.requireNonNull(rebuiltBook, "rebuiltBook");
    Objects.requireNonNull(rebuiltReferencePrices, "rebuiltReferencePrices");
    Objects.requireNonNull(rebuiltCircuitBreaker, "rebuiltCircuitBreaker");
    synchronized (runtimeState) {
      runtimeState.committedBook = rebuiltBook;
      runtimeState.referencePrices = rebuiltReferencePrices.copy();
      runtimeState.circuitBreaker = rebuiltCircuitBreaker.copy();
      runtimeState.committedMarketVersion = marketVersion;
    }
  }

  /** Publishes a trusted state that has already committed under the Exchange writer fence. */
  public void publishCommittedTrustedState(TrustedPriceState committed) {
    Objects.requireNonNull(committed, "committed");
    if (!rules.marketId().equals(committed.marketId())) {
      throw new IllegalArgumentException("trusted state belongs to another market");
    }
    synchronized (runtimeState) {
      if (committed.stateVersion() < runtimeState.trustedPriceState.stateVersion()) {
        throw new IllegalStateException("trusted state version cannot move backwards");
      }
      runtimeState.trustedPriceState = committed;
    }
  }

  /** Returns the latest committed trusted state for display readers. */
  public TrustedPriceState trustedPriceState() {
    synchronized (runtimeState) {
      return runtimeState.trustedPriceState;
    }
  }

  public void recoverFromDatabase() throws SQLException {
    synchronized (runtimeState) {
      RecoveredMarket recovered = marketRecovery.recover(rules.marketId(), now.get());
      runtimeState.committedBook = recovered.book();
      runtimeState.referencePrices = recovered.referencePrices().copy();
      runtimeState.trustedPriceState = recovered.trustedPriceState();
      runtimeState.recentInfluences = recovered.recentInfluences();
      runtimeState.circuitBreaker = recovered.circuitBreaker().copy();
      runtimeState.committedMarketVersion = recovered.marketVersion();
    }
  }

  /** Builds a protected quote from the most recently committed book and reference-price state. */
  public MarketQuote marketQuote(MarketDataService data) throws SQLException {
    Objects.requireNonNull(data, "data");
    MarketStatus status = repository.inTransaction(
        transaction -> transaction.marketState(rules.marketId()).status());
    Instant asOf = now.get();
    BigDecimal reference;
    LiquidityTier liquidityTier;
    BigDecimal bestBid;
    BigDecimal bestAsk;
    synchronized (runtimeState) {
      reference = runtimeState.trustedPriceState.trustedPrice();
      liquidityTier = runtimeState.trustedPriceState.liquidityTier();
      bestBid = runtimeState.committedBook.bestExecutable(OrderSide.BUY,
          price -> riskLimits.insideCage(price, reference)).map(Order::limitPrice).orElse(null);
      bestAsk = runtimeState.committedBook.bestExecutable(OrderSide.SELL,
          price -> riskLimits.insideCage(price, reference)).map(Order::limitPrice).orElse(null);
    }
    return data.quote(
        rules.marketId(), reference, liquidityTier, bestBid, bestAsk, status, asOf);
  }

  private RuntimeRiskSnapshot runtimeRisk(
      ExchangeTransaction tx, MarketState state, Instant recoveredAt) throws SQLException {
    if (runtimeState.committedMarketVersion == state.version()) {
      return new RuntimeRiskSnapshot(
          state, runtimeState.referencePrices.copy(), runtimeState.trustedPriceState,
          runtimeState.recentInfluences, runtimeState.circuitBreaker.copy());
    }
    try {
      RecoveredMarket recovered = marketRecovery.recover(tx, state, recoveredAt);
      return new RuntimeRiskSnapshot(
          recovered.state(), recovered.referencePrices(), recovered.trustedPriceState(),
          recovered.recentInfluences(), recovered.circuitBreaker());
    } catch (RuntimeException failure) {
      throw new SQLException("market runtime recovery failed", failure);
    }
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

  private OrderReceipt preflightRisk(OrderRequest request) throws SQLException {
    OrderRiskService.RejectReason rateLimitRejection =
        orderRisks.checkRateLimit(request.accountId(), now.get());
    if (rateLimitRejection != null) {
      return storedOrReject(request, rateLimitRejection);
    }
    synchronized (runtimeState) {
      if (runtimeState.committedMarketVersion == Long.MIN_VALUE) {
        return null;
      }
      BigDecimal reference = runtimeState.trustedPriceState.trustedPrice();
      if (parseType(request.type()) == OrderType.LIMIT
          && !riskLimits.insideCage(request.price(), reference)) {
        return storedOrReject(request, OrderRiskService.RejectReason.PRICE_OUTSIDE_CAGE);
      }
      if (wouldSelfTrade(request, runtimeState.committedBook, reference)) {
        return storedOrReject(request, OrderRiskService.RejectReason.SELF_TRADE);
      }
    }
    return null;
  }

  private OrderReceipt storedOrReject(OrderRequest request, OrderRiskService.RejectReason reason)
      throws SQLException {
    OrderReceipt stored = storedReceipt(request);
    if (stored != null) {
      return stored;
    }
    reject(reason);
    throw new AssertionError("reject must throw");
  }

  private boolean wouldSelfTrade(OrderRequest request, OrderBook book, BigDecimal referencePrice) {
    OrderType type = parseType(request.type());
    BigDecimal boundary = type == OrderType.LIMIT ? request.price() : request.slippageBoundary();
    OrderSide opposite = request.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    long remaining = request.quantity();
    for (Order maker : book.executableOrders(opposite,
        price -> riskLimits.insideCage(price, referencePrice))) {
      boolean crosses = request.side() == OrderSide.BUY
          ? maker.limitPrice().compareTo(boundary) <= 0
          : maker.limitPrice().compareTo(boundary) >= 0;
      if (!crosses) {
        break;
      }
      if (maker.accountId().equals(request.accountId())) {
        return true;
      }
      remaining -= Math.min(remaining, maker.remainingQuantity());
      if (remaining == 0) {
        return false;
      }
    }
    return false;
  }

  private static void reject(OrderRiskService.RejectReason reason) {
    throw new IllegalStateException(reason.name());
  }

  private Order createOrder(
      OrderRequest request, long prioritySequence, long structuralVersion,
      long feeVersion, Instant createdAt) {
    OrderType type = parseType(request.type());
    return new Order(ids.get(), request.requestId(), request.marketId(), request.accountId(),
        request.side(), type, type == OrderType.LIMIT ? TimeInForce.GTC : TimeInForce.IOC,
        request.price(), request.slippageBoundary(), request.quantity(), request.quantity(),
        OrderStatus.OPEN, prioritySequence, structuralVersion, feeVersion, createdAt, createdAt);
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
      ExchangeTransaction tx, Order order, Map<UUID, BigDecimal> currencyReservations,
      MarketFeeSchedule feeSchedule)
      throws SQLException {
    if (order.side() != OrderSide.BUY || order.type() != OrderType.LIMIT || isTerminal(order)) {
      return BigDecimal.ZERO;
    }
    BigDecimal reserved = currencyReservations.get(order.orderId());
    BigDecimal required = reservations.reserve(order, rulesWithFees(
        feeSchedule.rates(order.feeVersion()))).frozenCurrency();
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

  private MarketRules rulesWithFees(FeeRates rates) {
    return new MarketRules(rules.marketId(), rules.currencyId(), rules.basePrice(),
        rules.minPrice(), rules.maxPrice(), rules.tickSize(), rules.minQuantity(),
        rules.maxQuantity(), rules.priceScale(), rates.makerRate(), rates.takerRate());
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

  private void advancePlannedRisk(
      Trade trade, AtomicReference<TrustedPriceState> plannedTrusted,
      List<TradeInfluence> plannedInfluences) {
    pruneExpiredInfluences(plannedInfluences, trade.executedAt());
    long lot = Math.max(1L, Math.ceilDiv(discoveryQuantity, 20L));
    TrustedPriceState classified = plannedTrusted.get().withLiquidityTier(
        liquidityClassifier.classify(plannedInfluences, trade.executedAt(), lot).tier());
    TrustedPriceEngine.Result trusted = trustedPriceEngine.evaluate(
        classified, trustedPolicy, trade, plannedInfluences,
        discoveryQuantity, rules.priceScale());
    plannedInfluences.add(trusted.influence());
    plannedTrusted.set(trusted.state());
  }

  private RiskUpdate updateRiskState(
      ExchangeTransaction tx, MarketState before, long prioritySequence, long matchSequence,
      List<Trade> trades, ReferencePriceTracker prices, TrustedPriceState trustedPriceState,
      List<TradeInfluence> recentInfluences, CircuitBreaker breaker) throws SQLException {
    MarketStatus status = before.status();
    BigDecimal lastPrice = before.lastPrice();
    Instant haltedUntil = before.haltedUntil();
    long lot = Math.max(1L, Math.ceilDiv(discoveryQuantity, 20L));
    if (!trades.isEmpty()) {
      ensureTrustedState(tx, trustedPriceState);
    }
    for (Trade trade : trades) {
      pruneExpiredInfluences(recentInfluences, trade.executedAt());
      BigDecimal previousTrustedPrice = trustedPriceState.trustedPrice();
      trustedPriceState = trustedPriceState.withLiquidityTier(
          liquidityClassifier.classify(recentInfluences, trade.executedAt(), lot).tier());
      TrustedPriceEngine.Result trusted = trustedPriceEngine.evaluate(
          trustedPriceState, trustedPolicy, trade, recentInfluences,
          discoveryQuantity, rules.priceScale());
      tx.insertTradeInfluence(trusted.influence());
      tx.updateTrustedPriceState(trusted.state(), trustedPriceState.stateVersion());
      tx.recordTradeCandle(new Candle(
          trade.marketId(), candleBucket(trade.executedAt()), trade.price(), trade.price(),
          trade.price(), trade.price(), trade.quantity(),
          trade.price().multiply(BigDecimal.valueOf(trade.quantity()))));
      BehaviorRiskDecision previousBehavior = behaviorRiskEvaluator.evaluate(
          rules.marketId(), trusted.influence().pairKey(), trusted.state().liquidityTier(),
          recentInfluences, trade.executedAt());
      recentInfluences.add(trusted.influence());
      trustedPriceState = trusted.state();
      BehaviorRiskDecision behavior = behaviorRiskEvaluator.evaluate(
          rules.marketId(), trusted.influence().pairKey(), trustedPriceState.liquidityTier(),
          recentInfluences, trade.executedAt());
      if (behavior.action().isEscalationFrom(previousBehavior.action())) {
        if (behavior.action() == BehaviorRiskAction.ALERT) {
          tx.insertHighAlert(ids.get(), rules.marketId(), "PAIR_BEHAVIOR_ALERT",
              encodeBehaviorAlert(behavior), trade.executedAt());
        } else if (behavior.action() == BehaviorRiskAction.PAIR_COOLDOWN) {
          tx.insertHighAlert(ids.get(), rules.marketId(), "PAIR_BEHAVIOR_COOLDOWN",
              encodeBehaviorAlert(behavior), trade.executedAt());
        }
      }

      prices.record(trade.price(), trade.quantity(), trade.executedAt());
      lastPrice = trade.price();
      TradePermission permission = breaker.onPrice(
          trustedPriceState.trustedPrice(), previousTrustedPrice, trade.executedAt());
      if (!permission.allowed()) {
        status = MarketStatus.HALTED;
        haltedUntil = permission.haltUntil().orElseThrow();
        if (permission.level() == 2) {
          tx.insertHighAlert(ids.get(), rules.marketId(), "CIRCUIT_BREAKER_LEVEL_2",
              encodeLevelTwoAlert(previousTrustedPrice, trustedPriceState.trustedPrice()),
              trade.executedAt());
        }
      }
    }
    BigDecimal displayReference = displayTrustedPrice(trustedPriceState.trustedPrice());
    MarketState state = new MarketState(
        before.marketId(), status, prioritySequence, matchSequence,
        displayReference, lastPrice, haltedUntil, prices.discoveryQuantity(), breaker.level(),
        before.version() + 1);
    return new RiskUpdate(state, trustedPriceState, recentInfluences);
  }

  private void ensureTrustedState(
      ExchangeTransaction tx, TrustedPriceState trustedPriceState) throws SQLException {
    try {
      tx.trustedMarketSnapshot(rules.marketId(), Instant.EPOCH, Instant.EPOCH);
    } catch (UnsupportedOperationException unsupported) {
      return;
    } catch (SQLException missing) {
      if (missing.getMessage() == null
          || !missing.getMessage().startsWith("trusted market state does not exist:")) {
        throw missing;
      }
      tx.insertTrustedPriceState(trustedPriceState);
    }
  }

  private void pruneExpiredInfluences(
      List<TradeInfluence> influences, Instant evaluatedAt) {
    Instant budgetCutoff = evaluatedAt.minus(trustedPolicy.budgetWindow());
    Instant confidenceCutoff = evaluatedAt.minus(trustedPolicy.confidenceWindow());
    Instant cutoff = budgetCutoff.isBefore(confidenceCutoff)
        ? budgetCutoff : confidenceCutoff;
    influences.removeIf(influence -> influence.executedAt().isBefore(cutoff));
  }

  private static Instant candleBucket(Instant occurredAt) {
    return Instant.ofEpochSecond(Math.floorDiv(occurredAt.getEpochSecond(), 60L) * 60L);
  }

  private BigDecimal displayTrustedPrice(BigDecimal trustedPrice) {
    BigDecimal bounded = trustedPrice.max(rules.minPrice()).min(rules.maxPrice());
    return bounded.divide(rules.tickSize(), 0, RoundingMode.HALF_UP)
        .multiply(rules.tickSize()).setScale(rules.priceScale(), RoundingMode.HALF_UP);
  }

  private void reached(SettlementStage stage) {
    try {
      observer.reached(stage);
    } catch (RuntimeException failure) {
      throw new SettlementObservationFailure(failure);
    }
  }

  private void enterRecovery(String marketId, Throwable failure) {
    try {
      repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        tx.updateMarketState(new MarketState(state.marketId(), MarketStatus.RECOVERING,
            state.prioritySequence(), state.matchSequence(), state.referencePrice(),
            state.lastPrice(), state.haltedUntil(), state.discoveryQuantity(),
            state.circuitBreakerLevel(), state.version() + 1), state.version());
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

  private static String encodeBehaviorAlert(BehaviorRiskDecision decision) {
    JsonObject alert = new JsonObject();
    alert.addProperty("action", decision.action().name());
    alert.addProperty("pairKey", decision.pairKey());
    alert.addProperty("pairTrades", decision.pairTrades());
    alert.addProperty("marketTrades", decision.marketTrades());
    alert.addProperty("pairConcentration", decision.pairConcentration().toPlainString());
    alert.addProperty("directionalPressure", decision.directionalPressure().toPlainString());
    JsonArray evidence = new JsonArray();
    decision.evidence().stream().sorted().forEach(value -> evidence.add(value.name()));
    alert.add("evidence", evidence);
    decision.cooldownUntil().ifPresent(value ->
        alert.addProperty("cooldownUntil", value.toString()));
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
      MarketState state, ReferencePriceTracker referencePrices,
      TrustedPriceState trustedPriceState, List<TradeInfluence> recentInfluences,
      CircuitBreaker circuitBreaker) {
    private RuntimeRiskSnapshot {
      recentInfluences = List.copyOf(recentInfluences);
    }
  }

  private record RiskUpdate(
      MarketState state, TrustedPriceState trustedPriceState,
      List<TradeInfluence> recentInfluences) {
    private RiskUpdate {
      recentInfluences = List.copyOf(recentInfluences);
    }
  }

  private record MarketCoordinationKey(Object repositoryKey, String marketId) {}

  private static final class SettlementObservationFailure extends RuntimeException {
    private final RuntimeException original;

    private SettlementObservationFailure(RuntimeException original) {
      super(original);
      this.original = original;
    }

    private RuntimeException original() {
      return original;
    }
  }

  private static final class MarketRuntimeState {
    private OrderBook committedBook;
    private ReferencePriceTracker referencePrices;
    private TrustedPriceState trustedPriceState;
    private List<TradeInfluence> recentInfluences;
    private CircuitBreaker circuitBreaker;
    private long committedMarketVersion;

    private MarketRuntimeState(
        OrderBook committedBook, ReferencePriceTracker referencePrices,
        TrustedPriceState trustedPriceState, List<TradeInfluence> recentInfluences,
        CircuitBreaker circuitBreaker, long committedMarketVersion) {
      this.committedBook = committedBook;
      this.referencePrices = referencePrices;
      this.trustedPriceState = trustedPriceState;
      this.recentInfluences = List.copyOf(recentInfluences);
      this.circuitBreaker = circuitBreaker;
      this.committedMarketVersion = committedMarketVersion;
    }
  }

  private record TransactionOutcome(
      OrderReceipt receipt, SettlementPlan plan, OrderBook book,
      ReferencePriceTracker referencePrices, TrustedPriceState trustedPriceState,
      List<TradeInfluence> recentInfluences, CircuitBreaker circuitBreaker,
      long marketVersion, boolean duplicate) {
    private TransactionOutcome {
      recentInfluences = recentInfluences == null ? null : List.copyOf(recentInfluences);
    }

    private static TransactionOutcome duplicate(OrderReceipt receipt) {
      return new TransactionOutcome(
          receipt, null, null, null, null, null, null, Long.MIN_VALUE, true);
    }

    private static TransactionOutcome committed(
        OrderReceipt receipt, SettlementPlan plan, OrderBook book,
        ReferencePriceTracker referencePrices, TrustedPriceState trustedPriceState,
        List<TradeInfluence> recentInfluences, CircuitBreaker circuitBreaker, long marketVersion) {
      return new TransactionOutcome(receipt, plan, book, referencePrices, trustedPriceState,
          recentInfluences, circuitBreaker, marketVersion, false);
    }
  }

  private record ForceCancelOutcome(OrderReceipt receipt, OrderBook book, boolean duplicate) {
    private static ForceCancelOutcome duplicate(OrderReceipt receipt) {
      return new ForceCancelOutcome(receipt, null, true);
    }

    private static ForceCancelOutcome committed(OrderReceipt receipt, OrderBook book) {
      return new ForceCancelOutcome(receipt, book, false);
    }
  }
}
