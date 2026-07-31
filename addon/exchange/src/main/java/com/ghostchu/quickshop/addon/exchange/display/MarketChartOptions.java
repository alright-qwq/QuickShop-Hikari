package com.ghostchu.quickshop.addon.exchange.display;

/** Immutable feature switches for market map chart rendering. */
public record MarketChartOptions(
    boolean professionalLayout,
    boolean includeLiveCandle,
    boolean showVolume,
    boolean showLatestPriceLine,
    boolean showTrustedPriceLine,
    boolean showGapMarkers) {

  public MarketChartOptions(boolean professionalLayout, boolean includeLiveCandle,
                            boolean showVolume, boolean showLatestPriceLine) {
    this(professionalLayout, includeLiveCandle, showVolume, showLatestPriceLine, true, true);
  }

  public static MarketChartOptions defaults() {
    return new MarketChartOptions(true, true, true, true, true, true);
  }
}
