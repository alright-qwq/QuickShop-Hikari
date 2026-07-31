package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.PriceSample;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.risk.TradePermission;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityClassifier;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceEngine;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.MarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.repository.MarketTradeSample;
import com.ghostchu.quickshop.addon.exchange.repository.TrustedMarketSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class OrderBookRecoveryService {
  static final long DISCOVERY_QUANTITY = 100;
  static final Duration REFERENCE_WINDOW = Duration.ofMinutes(5);

  private final ExchangeRepository repository;
  private final MarketRules rules;
  private final RiskLimits riskLimits;
  private final long discoveryQuantity;
  private final TrustedPricePolicy trustedPolicy;
  private final LiquidityClassifier liquidityClassifier;
  private final TrustedPriceEngine trustedPriceEngine;

  public OrderBookRecoveryService(
      ExchangeRepository repository, MarketRules rules, RiskLimits riskLimits) {
    this(repository, rules, riskLimits, DISCOVERY_QUANTITY);
  }

  public OrderBookRecoveryService(
      ExchangeRepository repository, MarketRules rules, RiskLimits riskLimits,
      long discoveryQuantity) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.riskLimits = Objects.requireNonNull(riskLimits, "riskLimits");
    if (discoveryQuantity < 10L) {
      throw new IllegalArgumentException("recovery discovery quantity must be at least 10");
    }
    this.discoveryQuantity = discoveryQuantity;
    this.trustedPolicy = TrustedPricePolicy.defaults();
    this.liquidityClassifier = new LiquidityClassifier(trustedPolicy);
    this.trustedPriceEngine = new TrustedPriceEngine();
  }

  public RecoveredMarket recover(String marketId, Instant recoveredAt) throws SQLException {
    Objects.requireNonNull(recoveredAt, "recoveredAt");
    try {
      return repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        return recover(tx, state, recoveredAt);
      });
    } catch (SQLException | RuntimeException failure) {
      enterRecovery(marketId, failure);
      throw failure;
    }
  }

  private void enterRecovery(String marketId, Exception originalFailure) {
    try {
      repository.inTransaction(tx -> {
        MarketState state = tx.marketState(marketId);
        if (state.status() == MarketStatus.OPEN) {
          tx.updateMarketState(new MarketState(
              state.marketId(), MarketStatus.RECOVERING, state.prioritySequence(),
              state.matchSequence(), state.referencePrice(), state.lastPrice(),
              state.haltedUntil(), state.discoveryQuantity(), state.circuitBreakerLevel(),
              Math.addExact(state.version(), 1)), state.version());
        }
        return null;
      });
    } catch (SQLException | RuntimeException recoveryFailure) {
      originalFailure.addSuppressed(recoveryFailure);
    }
  }

  RecoveredMarket recover(ExchangeTransaction tx, MarketState state, Instant recoveredAt)
      throws SQLException {
    requireMarket(state);
    MarketSnapshot snapshot =
        tx.marketSnapshot(state, recoveredAt.minus(REFERENCE_WINDOW));
    validateSnapshot(snapshot);
    OrderBook book = rebuildBook(snapshot);
    boolean missingDiscovery = state.discoveryQuantity() == null;
    boolean missingBreaker = state.circuitBreakerLevel() == null;
    if (missingDiscovery != missingBreaker) {
      throw new IllegalStateException("market risk metadata is partially reconstructed");
    }
    ReferencePriceTracker referencePrices;
    CircuitBreaker circuitBreaker;
    MarketState recoveredState = state;
    List<MarketTradeSample> replayedHistory = null;
    if (missingDiscovery) {
      ReplayedRisk replayed = replayV1History(tx, state, snapshot, recoveredAt);
      recoveredState = new MarketState(
          state.marketId(), state.status(), state.prioritySequence(), state.matchSequence(),
          state.referencePrice(), state.lastPrice(), state.haltedUntil(),
          replayed.referencePrices().discoveryQuantity(), replayed.circuitBreaker().level(),
          state.version() + 1);
      tx.updateMarketState(recoveredState, state.version());
      referencePrices = replayed.referencePrices();
      circuitBreaker = replayed.circuitBreaker();
      replayedHistory = replayed.tradeHistory();
    } else {
      List<PriceSample> samples = snapshot.recentTrades().stream()
          .map(sample -> new PriceSample(sample.price(), sample.quantity(), sample.executedAt()))
          .toList();
      referencePrices = ReferencePriceTracker.restored(
          rules.basePrice(), discoveryQuantity, REFERENCE_WINDOW, rules.priceScale(),
          state.discoveryQuantity(), samples);
      circuitBreaker = CircuitBreaker.restored(
          riskLimits, state.circuitBreakerLevel(), state.haltedUntil());
    }

    TrustedRecovery trusted = recoverTrustedMarket(
        tx, recoveredState, recoveredAt, replayedHistory);
    return new RecoveredMarket(book, referencePrices, trusted.state(), trusted.influences(),
        circuitBreaker, recoveredState);
  }

  private TrustedRecovery recoverTrustedMarket(
      ExchangeTransaction tx, MarketState state, Instant recoveredAt,
      List<MarketTradeSample> replayedHistory) throws SQLException {
    try {
      TrustedMarketSnapshot snapshot = tx.trustedMarketSnapshot(
          state.marketId(), recoveredAt.minus(trustedPolicy.budgetWindow()),
          recoveredAt.minus(trustedPolicy.confidenceWindow()));
      if (isPristineLegacySeed(snapshot, state)) {
        List<MarketTradeSample> history = replayedHistory == null
            ? loadTradeHistory(tx, state.marketId()) : replayedHistory;
        return replayTrustedHistory(
            tx, state, recoveredAt, history, snapshot.state(), false);
      }
      validateTrustedSnapshot(snapshot, state, recoveredAt);
      return new TrustedRecovery(snapshot.state(), snapshot.influences());
    } catch (UnsupportedOperationException unsupported) {
      return temporaryTrustedRecovery(state, recoveredAt);
    } catch (SQLException missing) {
      if (!isMissingTrustedState(missing)) {
        throw missing;
      }
      List<MarketTradeSample> history = replayedHistory == null
          ? loadTradeHistory(tx, state.marketId()) : replayedHistory;
      return migrateTrustedMarket(tx, state, recoveredAt, history);
    }
  }

  private TrustedRecovery migrateTrustedMarket(
      ExchangeTransaction tx, MarketState state, Instant recoveredAt,
      List<MarketTradeSample> history) throws SQLException {
    BigDecimal initialPrice = state.referencePrice() == null
        ? rules.basePrice() : state.referencePrice();
    Instant initialTime = history.isEmpty() ? recoveredAt : history.get(0).executedAt();
    TrustedPriceState trustedState = new TrustedPriceState(
        state.marketId(), initialPrice, initialPrice, initialTime,
        LiquidityTier.LOW, 1, 0, 0);
    return replayTrustedHistory(
        tx, state, recoveredAt, history, trustedState, true);
  }

  private TrustedRecovery replayTrustedHistory(
      ExchangeTransaction tx, MarketState state, Instant recoveredAt,
      List<MarketTradeSample> history, TrustedPriceState trustedState,
      boolean insertInitialState) throws SQLException {
    validateTradeHistory(history, state, recoveredAt);
    if (insertInitialState) {
      tx.insertTrustedPriceState(trustedState);
    }
    ArrayList<TradeInfluence> influences = new ArrayList<>();
    long lot = Math.max(1L, Math.ceilDiv(discoveryQuantity, 20L));
    for (MarketTradeSample sample : history) {
      pruneExpiredInfluences(influences, sample.executedAt());
      trustedState = trustedState.withLiquidityTier(
          liquidityClassifier.classify(influences, sample.executedAt(), lot).tier());
      TrustedPriceEngine.Result evaluated = trustedPriceEngine.evaluate(
          trustedState, trustedPolicy, replayTrade(state.marketId(), sample), influences,
          discoveryQuantity, rules.priceScale());
      tx.insertTradeInfluence(evaluated.influence());
      tx.updateTrustedPriceState(evaluated.state(), trustedState.stateVersion());
      influences.add(evaluated.influence());
      trustedState = evaluated.state();
    }
    pruneExpiredInfluences(influences, recoveredAt);
    return new TrustedRecovery(trustedState, influences);
  }

  private void pruneExpiredInfluences(List<TradeInfluence> influences, Instant evaluatedAt) {
    Instant budgetCutoff = evaluatedAt.minus(trustedPolicy.budgetWindow());
    Instant confidenceCutoff = evaluatedAt.minus(trustedPolicy.confidenceWindow());
    Instant cutoff = budgetCutoff.isBefore(confidenceCutoff)
        ? budgetCutoff : confidenceCutoff;
    int expired = 0;
    while (expired < influences.size()
        && influences.get(expired).executedAt().isBefore(cutoff)) {
      expired++;
    }
    if (expired > 0) {
      influences.subList(0, expired).clear();
    }
  }

  private static boolean isPristineLegacySeed(
      TrustedMarketSnapshot snapshot, MarketState state) {
    return snapshot != null && snapshot.state() != null
        && state.matchSequence() > 0
        && snapshot.state().lastMatchSequence() == 0
        && snapshot.state().stateVersion() == 0
        && snapshot.influences().isEmpty()
        && snapshot.adjustments().isEmpty()
        && snapshot.state().trustedPrice().compareTo(snapshot.state().guidancePrice()) == 0;
  }

  private static List<MarketTradeSample> loadTradeHistory(
      ExchangeTransaction tx, String marketId) throws SQLException {
    ArrayList<MarketTradeSample> history = new ArrayList<>();
    tx.visitTradeHistory(marketId, history::add);
    return List.copyOf(history);
  }

  private static Trade replayTrade(String marketId, MarketTradeSample sample) {
    return new Trade(sample.tradeId(), marketId,
        replayOrderId(sample.tradeId(), "maker"), replayOrderId(sample.tradeId(), "taker"),
        sample.buyerAccountId(), sample.sellerAccountId(), sample.price(), sample.quantity(),
        BigDecimal.ZERO, BigDecimal.ZERO, sample.matchSequence(), sample.executedAt());
  }

  private static UUID replayOrderId(UUID tradeId, String role) {
    return UUID.nameUUIDFromBytes(
        (tradeId + ":" + role).getBytes(StandardCharsets.UTF_8));
  }

  private TrustedRecovery temporaryTrustedRecovery(MarketState state, Instant recoveredAt) {
    BigDecimal initialPrice = state.referencePrice() == null
        ? rules.basePrice() : state.referencePrice();
    return new TrustedRecovery(new TrustedPriceState(
        state.marketId(), initialPrice, initialPrice, recoveredAt,
        LiquidityTier.LOW, 1, state.matchSequence(), 0), List.of());
  }

  private static boolean isMissingTrustedState(SQLException failure) {
    return failure.getMessage() != null
        && failure.getMessage().startsWith("trusted market state does not exist:");
  }

  private static void validateTrustedSnapshot(
      TrustedMarketSnapshot snapshot, MarketState marketState, Instant recoveredAt) {
    if (snapshot == null || snapshot.state() == null
        || !marketState.marketId().equals(snapshot.state().marketId())
        || snapshot.state().lastMatchSequence() != marketState.matchSequence()
        || snapshot.state().lastEvaluatedAt().isAfter(recoveredAt)) {
      throw new IllegalStateException("trusted market state does not match market recovery");
    }
    long previousSequence = 0;
    Instant previousTime = null;
    Set<UUID> tradeIds = new HashSet<>();
    for (TradeInfluence influence : snapshot.influences()) {
      if (!marketState.marketId().equals(influence.marketId())
          || influence.matchSequence() <= previousSequence
          || influence.matchSequence() > snapshot.state().lastMatchSequence()
          || (previousTime != null && influence.executedAt().isBefore(previousTime))
          || influence.executedAt().isAfter(recoveredAt)
          || !tradeIds.add(influence.tradeId())
          || !TradeInfluence.pairKey(
              influence.buyerAccountId(), influence.sellerAccountId())
              .equals(influence.pairKey())) {
        throw new IllegalStateException("trusted market influence history is invalid");
      }
      previousSequence = influence.matchSequence();
      previousTime = influence.executedAt();
    }
    Instant previousAdjustment = null;
    Set<UUID> adjustmentIds = new HashSet<>();
    for (var adjustment : snapshot.adjustments()) {
      if (!marketState.marketId().equals(adjustment.marketId())
          || (previousAdjustment != null && adjustment.adjustedAt().isBefore(previousAdjustment))
          || adjustment.adjustedAt().isAfter(recoveredAt)
          || !adjustmentIds.add(adjustment.adjustmentId())) {
        throw new IllegalStateException("trusted market adjustment history is invalid");
      }
      previousAdjustment = adjustment.adjustedAt();
    }
  }

  private static void validateTradeHistory(
      List<MarketTradeSample> history, MarketState state, Instant recoveredAt) {
    long previousSequence = 0;
    Instant previousTime = null;
    Set<UUID> tradeIds = new HashSet<>();
    for (MarketTradeSample sample : history) {
      if (sample.matchSequence() <= previousSequence
          || sample.matchSequence() > state.matchSequence()
          || (previousTime != null && sample.executedAt().isBefore(previousTime))
          || sample.executedAt().isAfter(recoveredAt)
          || !tradeIds.add(sample.tradeId())) {
        throw new IllegalStateException("full trade history is not deterministic");
      }
      previousSequence = sample.matchSequence();
      previousTime = sample.executedAt();
    }
    if (previousSequence != state.matchSequence()) {
      throw new IllegalStateException("full trade history does not match market state");
    }
  }

  private ReplayedRisk replayV1History(
      ExchangeTransaction tx, MarketState state, MarketSnapshot snapshot, Instant recoveredAt)
      throws SQLException {
    ReferencePriceTracker prices = new ReferencePriceTracker(
        rules.basePrice(), discoveryQuantity, REFERENCE_WINDOW, rules.priceScale());
    CircuitBreaker breaker = new CircuitBreaker(riskLimits);
    ReplayCursor cursor = new ReplayCursor(rules.basePrice());
    ArrayList<MarketTradeSample> history = new ArrayList<>();
    tx.visitTradeHistory(state.marketId(), sample -> {
      if (sample.matchSequence() <= cursor.matchSequence
          || sample.matchSequence() > state.matchSequence()
          || (cursor.executedAt != null && sample.executedAt().isBefore(cursor.executedAt))) {
        throw new IllegalStateException("full trade history is not deterministic");
      }
      history.add(sample);
      TradePermission permission =
          breaker.onPrice(sample.price(), cursor.referencePrice, sample.executedAt());
      prices.record(sample.price(), sample.quantity(), sample.executedAt());
      if (permission.allowed()) {
        cursor.referencePrice = prices.referenceAt(sample.executedAt());
      }
      cursor.matchSequence = sample.matchSequence();
      cursor.executedAt = sample.executedAt();
    });
    if (cursor.matchSequence != snapshot.maximumMatchSequence()
        || cursor.referencePrice.compareTo(state.referencePrice()) != 0) {
      throw new IllegalStateException("full trade history does not match market state");
    }
    prices.referenceAt(recoveredAt);
    return new ReplayedRisk(prices,
        CircuitBreaker.restored(riskLimits, breaker.level(), state.haltedUntil()),
        List.copyOf(history));
  }

  private static OrderBook rebuildBook(MarketSnapshot snapshot) {
    OrderBook book = new OrderBook();
    snapshot.openOrders().stream()
        .map(ExchangeTransaction.PersistedOrder::order)
        .sorted(Comparator.comparingLong(Order::prioritySequence))
        .forEach(book::add);
    return book;
  }

  private void requireMarket(MarketState state) {
    if (state == null || !rules.marketId().equals(state.marketId())) {
      throw new IllegalArgumentException("market state does not match recovery rules");
    }
  }

  private static void validateSnapshot(MarketSnapshot snapshot) {
    MarketState state = snapshot.state();
    if (state.prioritySequence() < 0 || state.matchSequence() < 0
        || state.prioritySequence() == Long.MAX_VALUE || state.matchSequence() == Long.MAX_VALUE
        || snapshot.maximumPrioritySequence() > state.prioritySequence()
        || snapshot.maximumMatchSequence() > state.matchSequence()) {
      throw new IllegalStateException("market sequence snapshot is invalid");
    }
    Set<UUID> orderIds = new HashSet<>();
    Set<Long> priorities = new HashSet<>();
    for (ExchangeTransaction.PersistedOrder persisted : snapshot.openOrders()) {
      Order order = persisted.order();
      if (!state.marketId().equals(order.marketId())
          || (order.status() != OrderStatus.OPEN
              && order.status() != OrderStatus.PARTIALLY_FILLED)
          || order.remainingQuantity() <= 0
          || order.prioritySequence() > state.prioritySequence()
          || !orderIds.add(order.orderId()) || !priorities.add(order.prioritySequence())) {
        throw new IllegalStateException("open order snapshot is invalid");
      }
    }
    long previousMatch = 0;
    Instant previousTime = null;
    for (MarketTradeSample sample : snapshot.recentTrades()) {
      if (sample.matchSequence() <= previousMatch
          || sample.matchSequence() > state.matchSequence()
          || (previousTime != null && sample.executedAt().isBefore(previousTime))) {
        throw new IllegalStateException("trade sample snapshot is invalid");
      }
      previousMatch = sample.matchSequence();
      previousTime = sample.executedAt();
    }
  }

  private record ReplayedRisk(
      ReferencePriceTracker referencePrices, CircuitBreaker circuitBreaker,
      List<MarketTradeSample> tradeHistory) {}

  private record TrustedRecovery(
      TrustedPriceState state, List<TradeInfluence> influences) {
    private TrustedRecovery {
      influences = List.copyOf(influences);
    }
  }

  private static final class ReplayCursor {
    private java.math.BigDecimal referencePrice;
    private long matchSequence;
    private Instant executedAt;

    private ReplayCursor(java.math.BigDecimal referencePrice) {
      this.referencePrice = referencePrice;
    }
  }
}
