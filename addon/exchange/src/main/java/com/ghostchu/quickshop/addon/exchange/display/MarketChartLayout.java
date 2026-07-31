package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Optional;

/** Size-aware, non-overlapping regions for the professional map chart. */
public record MarketChartLayout(
    Density density,
    Rect header,
    Rect plot,
    Optional<Rect> priceAxis,
    Optional<Rect> volume,
    Optional<Rect> timeAxis,
    Optional<Rect> legend,
    Optional<Rect> confidence) {

  public MarketChartLayout {
    if (density == null || header == null || plot == null || priceAxis == null || volume == null
        || timeAxis == null || legend == null || confidence == null) {
      throw new NullPointerException("chart layout regions cannot be null");
    }
  }

  public static MarketChartLayout forDimensions(MarketChartDimensions dimensions) {
    int width = dimensions.pixelWidth();
    int height = dimensions.pixelHeight();
    Density density = dimensions.rows() == 2 ? Density.FULL
        : dimensions.columns() == 2 ? Density.WIDE : Density.COMPACT;
    int margin = 4;
    int axisWidth = 31;
    int headerHeight = density == Density.FULL ? 15 : 10;
    Rect header = new Rect(margin, margin, width - margin * 2, headerHeight);

    if (density == Density.COMPACT) {
      Rect plot = new Rect(margin, 18, width - margin * 2, height - 22);
      return new MarketChartLayout(density, header, plot, Optional.empty(), Optional.empty(),
          Optional.empty(), Optional.empty(), Optional.empty());
    }

    int priceLeft = width - margin - axisWidth;
    int plotTop = density == Density.FULL ? 23 : 18;
    int plotHeight = density == Density.FULL ? 135 : 75;
    Rect plot = new Rect(margin, plotTop, priceLeft - margin - 4, plotHeight);
    Rect priceAxis = new Rect(priceLeft, plotTop, axisWidth, plotHeight);
    if (density == Density.WIDE) {
      Rect volume = new Rect(margin, 97, plot.width(), 27);
      return new MarketChartLayout(density, header, plot, Optional.of(priceAxis),
          Optional.of(volume), Optional.empty(), Optional.empty(), Optional.empty());
    }

    Rect volume = new Rect(margin, 162, plot.width(), 35);
    Rect timeAxis = new Rect(margin, 201, width - margin * 2, 15);
    Rect legend = new Rect(margin, 220, 100, 12);
    Rect confidence = new Rect(108, 220, width - 112, 12);
    return new MarketChartLayout(density, header, plot, Optional.of(priceAxis),
        Optional.of(volume), Optional.of(timeAxis), Optional.of(legend), Optional.of(confidence));
  }

  public enum Density {
    COMPACT,
    WIDE,
    FULL
  }

  public record Rect(int left, int top, int width, int height) {
    public Rect {
      if (left < 0 || top < 0 || width <= 0 || height <= 0) {
        throw new IllegalArgumentException("chart rectangle must be positive");
      }
    }

    public int right() {
      return left + width - 1;
    }

    public int bottom() {
      return top + height - 1;
    }
  }
}
