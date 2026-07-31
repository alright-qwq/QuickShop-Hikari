package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MarketDisplaySnapshot(String marketId, String displayName, MarketQuote quote,
                                    List<Candle> candles, List<TrustedPricePoint> trustedPoints,
                                    LiquidityTier liquidityTier, long trustedStateVersion,
                                    Instant fromInclusive, Instant toExclusive) {
  public MarketDisplaySnapshot(String marketId, String displayName, MarketQuote quote,
                               List<Candle> candles, Instant fromInclusive, Instant toExclusive) {
    this(marketId, displayName, quote, candles, List.of(), quote.liquidityTier(), 0L,
        fromInclusive, toExclusive);
  }

  public MarketDisplaySnapshot {
    if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("market display identity is required");
    }
    Objects.requireNonNull(quote, "quote");
    candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
    trustedPoints = List.copyOf(Objects.requireNonNull(trustedPoints, "trustedPoints"));
    Objects.requireNonNull(liquidityTier, "liquidityTier");
    if (trustedStateVersion < 0) {
      throw new IllegalArgumentException("trusted state version must not be negative");
    }
    Objects.requireNonNull(fromInclusive, "fromInclusive");
    Objects.requireNonNull(toExclusive, "toExclusive");
    if (!fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("display snapshot range must be positive");
    }
  }

  public String fingerprint() {
    StringBuilder fingerprint = new StringBuilder()
        .append(quote.asOf()).append('|')
        .append(quote.lastPrice()).append('|')
        .append(quote.change24h()).append('|')
        .append(quote.bestBid()).append('|')
        .append(quote.bestAsk()).append('|')
        .append(quote.status());
    for (Candle candle : candles) {
      fingerprint.append('|').append(candle.bucketStart())
          .append(':').append(candle.open())
          .append(':').append(candle.high())
          .append(':').append(candle.low())
          .append(':').append(candle.close())
          .append(':').append(candle.volume())
          .append(':').append(candle.notional());
    }
    fingerprint.append("|trusted-tier=").append(liquidityTier)
        .append("|trusted-state=").append(trustedStateVersion);
    for (TrustedPricePoint point : trustedPoints) {
      fingerprint.append("|trusted=").append(point.at()).append(':').append(point.price());
    }
    return fingerprint.toString();
  }
}
