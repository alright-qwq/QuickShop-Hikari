package com.ghostchu.quickshop.addon.exchange.ui;

import java.util.List;
import java.util.Objects;

/** Immutable market list and overview generated from the same quote collection. */
public record MarketListSnapshot(List<MarketRow> markets, MarketOverviewSnapshot overview) {
  public MarketListSnapshot {
    markets = List.copyOf(Objects.requireNonNull(markets, "markets"));
    overview = Objects.requireNonNull(overview, "overview");
  }
}
