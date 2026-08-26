package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;

/** Immutable market-list value consumed by TNML page rendering only. */
public record MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                        BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                        long volume24h, MarketStatus status, String assetType, String symbol,
                        Long totalSupply, String securityStatus, BigDecimal volatility24h,
                        BigDecimal high24h, BigDecimal low24h, Long issuedSupply,
                        BigDecimal notional24h) {
  public MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                   BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                   long volume24h, MarketStatus status) {
    this(marketId, displayName, lastPrice, bestBid, bestAsk, change24h, volume24h, status,
        null, null, null, null, null, null, null, null, null);
  }
}
