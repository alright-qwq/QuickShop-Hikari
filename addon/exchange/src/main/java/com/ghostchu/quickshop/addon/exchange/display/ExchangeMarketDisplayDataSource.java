package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
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
        return new MarketDisplaySnapshot(marketId, market.displayName(), quote,
            List.copyOf(candlesByBucket.values()), fromInclusive, toExclusive);
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
                             CheckedCandleReader liveCandles) {
    public MarketAccess(String displayName, CheckedQuoteSupplier quote,
                        CheckedCandleReader persistedCandles) {
      this(displayName, quote, persistedCandles, (fromInclusive, toExclusive) -> List.of());
    }

    public MarketAccess {
      if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display name is required");
      }
      Objects.requireNonNull(quote, "quote");
      Objects.requireNonNull(persistedCandles, "persistedCandles");
      Objects.requireNonNull(liveCandles, "liveCandles");
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

  private static final class MarketDisplayReadException extends RuntimeException {
    private MarketDisplayReadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
