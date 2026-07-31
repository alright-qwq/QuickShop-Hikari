package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ExchangeMarketDisplayDataSource implements MarketDisplayDataSource {
  private final Map<String, MarketAccess> markets;
  private final Executor executor;

  public ExchangeMarketDisplayDataSource(Map<String, MarketAccess> markets, Executor executor) {
    this.markets = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(markets, "markets")));
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public CompletableFuture<MarketDisplaySnapshot> snapshot(
      String marketId, MarketChartPeriod period, Instant toExclusive) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(toExclusive, "toExclusive");
    MarketAccess market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    Instant fromInclusive = toExclusive.minus(period.duration());
    return CompletableFuture.supplyAsync(() -> {
      try {
        MarketQuote quote = market.quote().get();
        Map<Instant, Candle> candlesByBucket = new TreeMap<>();
        market.persistedCandles().load(fromInclusive, toExclusive)
            .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
        market.liveCandles().load(fromInclusive, toExclusive)
            .forEach(candle -> candlesByBucket.put(candle.bucketStart(), candle));
        List<TrustedPricePoint> trustedPoints = market.trustedPoints()
            .load(fromInclusive, toExclusive);
        TrustedPriceState trustedState = market.trustedState().get();
        if (trustedState != null) {
          Instant stateAt = trustedState.lastEvaluatedAt();
          if (stateAt.isBefore(fromInclusive)) {
            stateAt = fromInclusive;
          } else if (!stateAt.isBefore(toExclusive)) {
            stateAt = toExclusive.minusNanos(1);
          }
          TreeMap<Instant, TrustedPricePoint> byTime = new TreeMap<>();
          trustedPoints.forEach(point -> byTime.put(point.at(), point));
          if (byTime.isEmpty()
              || byTime.lastEntry().getValue().price().compareTo(trustedState.trustedPrice()) != 0) {
            byTime.putIfAbsent(stateAt, new TrustedPricePoint(stateAt, trustedState.trustedPrice()));
          }
          trustedPoints = List.copyOf(byTime.values());
        }
        LiquidityTier liquidityTier = trustedState == null
            ? quote.liquidityTier() : trustedState.liquidityTier();
        long trustedStateVersion = trustedState == null ? 0L : trustedState.stateVersion();
        return new MarketDisplaySnapshot(marketId, market.displayName(), quote,
            List.copyOf(candlesByBucket.values()), trustedPoints, liquidityTier,
            trustedStateVersion, fromInclusive, toExclusive);
      } catch (RuntimeException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new MarketDisplayReadException("failed to load display snapshot: " + marketId,
            failure);
      }
    }, executor);
  }

  public record MarketAccess(String displayName, CheckedQuoteSupplier quote,
                             CheckedCandleReader persistedCandles,
                             CheckedCandleReader liveCandles,
                             CheckedTrustedPointReader trustedPoints,
                             CheckedTrustedStateSupplier trustedState) {
    public MarketAccess(String displayName, CheckedQuoteSupplier quote,
                        CheckedCandleReader persistedCandles) {
      this(displayName, quote, persistedCandles, (fromInclusive, toExclusive) -> List.of());
    }

    public MarketAccess(String displayName, CheckedQuoteSupplier quote,
                        CheckedCandleReader persistedCandles, CheckedCandleReader liveCandles) {
      this(displayName, quote, persistedCandles, liveCandles,
          (fromInclusive, toExclusive) -> List.of(), () -> null);
    }

    public MarketAccess {
      if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display name is required");
      }
      Objects.requireNonNull(quote, "quote");
      Objects.requireNonNull(persistedCandles, "persistedCandles");
      Objects.requireNonNull(liveCandles, "liveCandles");
      if (trustedPoints == null) {
        trustedPoints = (fromInclusive, toExclusive) -> List.of();
      }
      if (trustedState == null) {
        trustedState = () -> null;
      }
    }
  }

  @FunctionalInterface
  public interface CheckedQuoteSupplier {
    MarketQuote get() throws Exception;
  }

  @FunctionalInterface
  public interface CheckedCandleReader {
    List<Candle> load(Instant fromInclusive, Instant toExclusive) throws Exception;
  }

  @FunctionalInterface
  public interface CheckedTrustedPointReader {
    List<TrustedPricePoint> load(Instant fromInclusive, Instant toExclusive) throws Exception;
  }

  @FunctionalInterface
  public interface CheckedTrustedStateSupplier {
    TrustedPriceState get() throws Exception;
  }

  private static final class MarketDisplayReadException extends RuntimeException {
    private MarketDisplayReadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
