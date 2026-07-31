package com.ghostchu.quickshop.addon.exchange.display;

import java.util.List;
import java.util.Objects;

public record MarketSignLines(List<MarketSignLine> lines) {
  public MarketSignLines {
    lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    if (lines.size() != 4) {
      throw new IllegalArgumentException("a market sign must contain exactly four lines");
    }
  }
}
