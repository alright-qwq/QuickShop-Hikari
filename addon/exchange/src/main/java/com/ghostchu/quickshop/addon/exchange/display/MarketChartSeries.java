package com.ghostchu.quickshop.addon.exchange.display;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record MarketChartSeries(List<ChartCandle> candles, BigDecimal minimumPrice,
                                BigDecimal maximumPrice) {
  public MarketChartSeries {
    candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
    Objects.requireNonNull(minimumPrice, "minimumPrice");
    Objects.requireNonNull(maximumPrice, "maximumPrice");
    if (maximumPrice.compareTo(minimumPrice) <= 0) {
      throw new IllegalArgumentException("chart price range must be positive");
    }
  }

  public boolean hasData() {
    return !candles.isEmpty();
  }

  public static MarketChartSeries empty() {
    return new MarketChartSeries(List.of(), BigDecimal.ZERO, BigDecimal.ONE);
  }
}
