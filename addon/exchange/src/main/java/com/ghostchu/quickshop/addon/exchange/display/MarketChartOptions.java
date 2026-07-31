package com.ghostchu.quickshop.addon.exchange.display;

/** Immutable feature switches for market map chart rendering. */
public record MarketChartOptions(
    boolean professionalLayout,
    boolean includeLiveCandle,
    boolean showVolume,
    boolean showLatestPriceLine,
    boolean showTrustedPriceLine,
  boolean showGapMarkers,
  MarketChartInterval fixedInterval) {

  public MarketChartOptions(boolean professionalLayout, boolean includeLiveCandle,
                            boolean showVolume, boolean showLatestPriceLine) {
    this(professionalLayout, includeLiveCandle, showVolume, showLatestPriceLine, true, true, null);
  }

  public MarketChartOptions(boolean professionalLayout, boolean includeLiveCandle,
                            boolean showVolume, boolean showLatestPriceLine,
                            boolean showTrustedPriceLine, boolean showGapMarkers) {
    this(professionalLayout, includeLiveCandle, showVolume, showLatestPriceLine,
        showTrustedPriceLine, showGapMarkers, null);
  }

  public static MarketChartOptions defaults() {
    return new MarketChartOptions(true, true, true, true, true, true, null);
  }

  public String fingerprint() {
    return professionalLayout + ":" + includeLiveCandle + ":" + showVolume + ":"
        + showLatestPriceLine + ":" + showTrustedPriceLine + ":" + showGapMarkers + ":"
        + (fixedInterval == null ? "auto" : fixedInterval.label());
  }
}
