package com.ghostchu.quickshop.addon.exchange.display;

import java.util.concurrent.CompletableFuture;

public interface DisplayScheduler {
  CompletableFuture<Void> updateMapFrame(MapFrameBinding frame, MarketChartImage image);

  CompletableFuture<Void> updateSign(MarketSignBinding sign, MarketSignLines lines);
}
