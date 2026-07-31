package com.ghostchu.quickshop.addon.exchange.display;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface MarketDisplayDataSource {
  CompletableFuture<MarketDisplaySnapshot> snapshot(
      String marketId, MarketChartPeriod period, Instant toExclusive);
}
