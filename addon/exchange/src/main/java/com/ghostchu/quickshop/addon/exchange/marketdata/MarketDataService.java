package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Builds read-only quotes from executed trades and their UTC-minute candles. */
public final class MarketDataService {
  private static final Duration TICKER_WINDOW = Duration.ofHours(24);
  private final CandleAggregator candles;
  private final ExchangeRepository repository;
  private final Map<String, BigDecimal> lastPrices = new ConcurrentHashMap<>();
  private final Map<String, Instant> currentBuckets = new HashMap<>();
  private final List<Consumer<TradeEvent>> auditConsumers = new CopyOnWriteArrayList<>();
  private final Map<UUID, Consumer<PlayerUpdate>> playerConsumers = new ConcurrentHashMap<>();
  private final Set<String> changedMarkets = ConcurrentHashMap.newKeySet();

  public MarketDataService(CandleAggregator candles) {
    this(candles, null);
  }

  public MarketDataService(CandleAggregator candles, ExchangeRepository repository) {
    this.candles = Objects.requireNonNull(candles, "candles");
    this.repository = repository;
  }

  public synchronized void recordTrade(
      String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    Instant bucket = bucketStart(occurredAt);
    Instant current = currentBuckets.get(marketId);
    if (current != null && bucket.isBefore(current)) {
      throw new IllegalArgumentException("market trades must be recorded in chronological order");
    }
    if (current != null && bucket.isAfter(current) && repository != null) {
      persistClosedCandle(marketId, current);
    }
    candles.record(marketId, price, quantity, occurredAt);
    currentBuckets.put(marketId, bucket);
    lastPrices.put(marketId, price);
    changedMarkets.add(marketId);
    TradeEvent event = new TradeEvent(marketId, price, quantity, occurredAt);
    for (Consumer<TradeEvent> consumer : auditConsumers) {
      try {
        consumer.accept(event);
      } catch (RuntimeException ignored) {
        // Audit publication is observational and cannot invalidate a committed trade.
      }
    }
  }

  /** Registers an audit sink that receives every committed trade. */
  public void addAuditConsumer(Consumer<TradeEvent> consumer) {
    auditConsumers.add(Objects.requireNonNull(consumer, "consumer"));
  }

  public void removeAuditConsumer(Consumer<TradeEvent> consumer) {
    auditConsumers.remove(Objects.requireNonNull(consumer, "consumer"));
  }

  /** Registers one player's view refresh callback. The runtime invokes {@link #publishPlayerUpdates()} every 20 ticks. */
  public void subscribePlayer(UUID playerId, Consumer<PlayerUpdate> consumer) {
    playerConsumers.put(Objects.requireNonNull(playerId, "playerId"),
        Objects.requireNonNull(consumer, "consumer"));
  }

  public void unsubscribePlayer(UUID playerId) {
    playerConsumers.remove(Objects.requireNonNull(playerId, "playerId"));
  }

  /** Publishes at most one coalesced update per subscribed player for the current scheduler tick. */
  public synchronized void publishPlayerUpdates() {
    if (changedMarkets.isEmpty()) {
      return;
    }
    PlayerUpdate update = new PlayerUpdate(Set.copyOf(changedMarkets));
    changedMarkets.clear();
    for (Consumer<PlayerUpdate> consumer : playerConsumers.values()) {
      try {
        consumer.accept(update);
      } catch (RuntimeException ignored) {
        // A disconnected player view must not stop refreshes for other viewers.
      }
    }
  }

  /** Persists every in-memory candle whose UTC minute ended before {@code asOf}. */
  public synchronized void flush(Instant asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (repository == null) {
      return;
    }
    Instant currentBucket = bucketStart(asOf);
    var iterator = currentBuckets.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, Instant> entry = iterator.next();
      if (entry.getValue().isBefore(currentBucket)) {
        persistClosedCandle(entry.getKey(), entry.getValue());
        iterator.remove();
      }
    }
  }

  public MarketQuote quote(String marketId, BigDecimal referencePrice, BigDecimal bestBid,
                           BigDecimal bestAsk, MarketStatus status, Instant asOf) {
    requireQuoteArguments(marketId, referencePrice, status, asOf);
    Instant from = asOf.minus(TICKER_WINDOW);
    Instant to = asOf.plusSeconds(60);
    Map<Instant, Candle> tickerByBucket = new TreeMap<>();
    loadPersistedCandles(marketId, from, to)
        .forEach(candle -> tickerByBucket.put(candle.bucketStart(), candle));
    candles.snapshots(marketId, from, to)
        .forEach(candle -> tickerByBucket.put(candle.bucketStart(), candle));
    List<Candle> ticker = tickerByBucket.values().stream()
        .sorted(Comparator.comparing(Candle::bucketStart)).toList();
    BigDecimal lastPrice = lastPrices.getOrDefault(marketId, referencePrice);
    long volume = ticker.stream().mapToLong(Candle::volume).reduce(0L, Math::addExact);
    BigDecimal notional = ticker.stream().map(Candle::notional)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal change = ticker.isEmpty() ? BigDecimal.ZERO : ticker.get(ticker.size() - 1).close()
        .subtract(ticker.getFirst().open())
        .divide(ticker.getFirst().open(), 8, RoundingMode.HALF_UP)
        .stripTrailingZeros();
    return new MarketQuote(marketId, lastPrice, referencePrice, bestBid, bestAsk, change,
        volume, notional, status, asOf);
  }

  public MarketQuote quote(String marketId, BigDecimal referencePrice, OrderBook book,
                           RiskLimits limits, MarketStatus status, Instant asOf) {
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(limits, "limits");
    requireQuoteArguments(marketId, referencePrice, status, asOf);
    BigDecimal bestBid = book.bestExecutable(OrderSide.BUY,
        price -> limits.insideCage(price, referencePrice)).map(Order::limitPrice).orElse(null);
    BigDecimal bestAsk = book.bestExecutable(OrderSide.SELL,
        price -> limits.insideCage(price, referencePrice)).map(Order::limitPrice).orElse(null);
    return quote(marketId, referencePrice, bestBid, bestAsk, status, asOf);
  }

  public List<DepthLevel> depth(OrderBook book, OrderSide side, BigDecimal referencePrice,
                                RiskLimits limits) {
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(referencePrice, "referencePrice");
    Objects.requireNonNull(limits, "limits");
    if (referencePrice.signum() <= 0) {
      throw new IllegalArgumentException("reference price must be positive");
    }
    Map<BigDecimal, Long> quantities = new LinkedHashMap<>();
    for (Order order : book.orders(side)) {
      quantities.merge(order.limitPrice(), order.remainingQuantity(), Math::addExact);
    }
    return quantities.entrySet().stream()
        .map(entry -> new DepthLevel(entry.getKey(), entry.getValue(),
            limits.insideCage(entry.getKey(), referencePrice)))
        .toList();
  }

  private static void requireQuoteArguments(String marketId, BigDecimal referencePrice,
                                             MarketStatus status, Instant asOf) {
    if (marketId == null || marketId.isBlank() || referencePrice == null
        || referencePrice.signum() <= 0 || status == null || asOf == null) {
      throw new IllegalArgumentException("invalid market quote request");
    }
  }

  private void persistClosedCandle(String marketId, Instant bucket) {
    Candle candle = candles.snapshot(marketId, bucket)
        .orElseThrow(() -> new IllegalStateException("missing closed candle"));
    try {
      repository.upsertCandle(candle);
      candles.discard(marketId, bucket);
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to persist closed candle", failure);
    }
  }

  private List<Candle> loadPersistedCandles(String marketId, Instant from, Instant to) {
    if (repository == null) {
      return List.of();
    }
    try {
      return repository.loadCandles(marketId, from, to);
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load market candles", failure);
    }
  }

  private static Instant bucketStart(Instant instant) {
    Objects.requireNonNull(instant, "occurredAt");
    return Instant.ofEpochSecond(Math.floorDiv(instant.getEpochSecond(), 60) * 60L);
  }

  public record DepthLevel(BigDecimal price, long quantity, boolean executable) {
    public DepthLevel {
      if (price == null || price.signum() <= 0 || quantity <= 0) {
        throw new IllegalArgumentException("invalid depth level");
      }
    }
  }

  public record TradeEvent(String marketId, BigDecimal price, long quantity, Instant occurredAt) {
    public TradeEvent {
      if (marketId == null || marketId.isBlank() || price == null || price.signum() <= 0
          || quantity <= 0 || occurredAt == null) {
        throw new IllegalArgumentException("invalid trade event");
      }
    }
  }

  public record PlayerUpdate(Set<String> marketIds) {
    public PlayerUpdate {
      marketIds = Set.copyOf(Objects.requireNonNull(marketIds, "marketIds"));
      if (marketIds.isEmpty()) {
        throw new IllegalArgumentException("player update requires a changed market");
      }
    }
  }
}
