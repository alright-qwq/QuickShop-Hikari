package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable read model for one market-detail workbench refresh. */
public record MarketDashboardSnapshot(
    MarketRow market,
    List<Candle> recentCandles,
    List<MarketDataService.DepthLevel> bids,
    List<MarketDataService.DepthLevel> asks,
    BigDecimal spread,
    BigDecimal spreadPercent,
    BigDecimal notional24h) {
  public MarketDashboardSnapshot(
      MarketRow market,
      List<Candle> recentCandles,
      List<MarketDataService.DepthLevel> bids,
      List<MarketDataService.DepthLevel> asks,
      BigDecimal spread,
      BigDecimal spreadPercent) {
    this(market, recentCandles, bids, asks, spread, spreadPercent, null);
  }

  public MarketDashboardSnapshot {
    market = Objects.requireNonNull(market, "market");
    recentCandles = List.copyOf(Objects.requireNonNull(recentCandles, "recentCandles"));
    bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
    asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
    requireNonNegative(spread, "spread");
    requireNonNegative(spreadPercent, "spreadPercent");
    requireNonNegative(notional24h, "notional24h");
  }

  private static void requireNonNegative(BigDecimal value, String name) {
    if (value != null && value.signum() < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }
}
