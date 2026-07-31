package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import java.math.BigDecimal;
import java.time.Instant;

/** Read-only market quote used by player views and operational consumers. */
public record MarketQuote(String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
                          LiquidityTier liquidityTier,
                          BigDecimal bestBid, BigDecimal bestAsk,
                          BigDecimal change24h, long volume24h, BigDecimal notional24h,
                          MarketStatus status, Instant asOf) {
  public MarketQuote {
    if (marketId == null || marketId.isBlank() || referencePrice == null
        || referencePrice.signum() <= 0 || liquidityTier == null
        || change24h == null || volume24h < 0
        || notional24h == null || notional24h.signum() < 0 || status == null || asOf == null) {
      throw new IllegalArgumentException("invalid market quote");
    }
  }

  public MarketQuote(
      String marketId, BigDecimal lastPrice, BigDecimal referencePrice,
      BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h, long volume24h,
      BigDecimal notional24h, MarketStatus status, Instant asOf) {
    this(marketId, lastPrice, referencePrice, LiquidityTier.LOW, bestBid, bestAsk,
        change24h, volume24h, notional24h, status, asOf);
  }
}
