package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record MarketChartSeries(
    MarketChartInterval interval,
    List<ChartCandle> candles,
    List<TrustedPricePoint> trustedPoints,
    List<ChartGap> gaps,
    BigDecimal minimum,
    BigDecimal maximum,
    BigDecimal latestRawPrice,
    BigDecimal latestTrustedPrice,
    LiquidityTier liquidityTier,
    boolean flat,
    boolean singleCandle) {
  public MarketChartSeries {
    Objects.requireNonNull(interval, "interval");
    candles = List.copyOf(Objects.requireNonNull(candles, "candles"));
    trustedPoints = List.copyOf(Objects.requireNonNull(trustedPoints, "trustedPoints"));
    gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps"));
    Objects.requireNonNull(minimum, "minimum");
    Objects.requireNonNull(maximum, "maximum");
    Objects.requireNonNull(latestRawPrice, "latestRawPrice");
    Objects.requireNonNull(latestTrustedPrice, "latestTrustedPrice");
    Objects.requireNonNull(liquidityTier, "liquidityTier");
    if (maximum.compareTo(minimum) <= 0) {
      throw new IllegalArgumentException("chart price range must be positive");
    }
    if (singleCandle != (candles.size() == 1)) {
      throw new IllegalArgumentException("single-candle flag does not match chart data");
    }
  }

  /** Compatibility constructor for the original raw-only renderer API. */
  public MarketChartSeries(List<ChartCandle> candles, BigDecimal minimumPrice,
                           BigDecimal maximumPrice) {
    this(MarketChartInterval.FIVE_MINUTES, candles, List.of(), List.of(), minimumPrice,
        maximumPrice, candles.isEmpty() ? BigDecimal.ZERO : candles.getLast().close(),
        BigDecimal.ZERO, LiquidityTier.LOW, false, candles.size() == 1);
  }

  public boolean hasData() {
    return !candles.isEmpty() || !trustedPoints.isEmpty();
  }

  public BigDecimal minimumPrice() {
    return minimum;
  }

  public BigDecimal maximumPrice() {
    return maximum;
  }

  public static MarketChartSeries empty() {
    return new MarketChartSeries(MarketChartInterval.FIVE_MINUTES, List.of(), List.of(), List.of(),
        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, LiquidityTier.LOW,
        false, false);
  }
}
