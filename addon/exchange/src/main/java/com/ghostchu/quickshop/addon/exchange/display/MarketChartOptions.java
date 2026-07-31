package com.ghostchu.quickshop.addon.exchange.display;

/** Immutable feature switches for market map chart rendering. */
public record MarketChartOptions(
    boolean professionalLayout,
    boolean includeLiveCandle,
    boolean showVolume,
    boolean showLatestPriceLine) {

  public static MarketChartOptions defaults() {
    return new MarketChartOptions(true, true, true, true);
  }
}
