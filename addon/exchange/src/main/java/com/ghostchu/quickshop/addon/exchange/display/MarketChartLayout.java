package com.ghostchu.quickshop.addon.exchange.display;

/** Size-aware regions for the professional map chart. */
public record MarketChartLayout(Density density, Rect header, Rect plot, Rect priceAxis,
                                Rect volume, Rect timeAxis) {
  public static MarketChartLayout forDimensions(MarketChartDimensions dimensions) {
    int width = dimensions.pixelWidth();
    int height = dimensions.pixelHeight();
    Density density = dimensions.rows() == 2 ? Density.FULL
        : dimensions.columns() == 2 ? Density.WIDE : Density.COMPACT;
    int margin = 4;
    int axisWidth = density == Density.COMPACT ? 25 : 31;
    int headerHeight = density == Density.FULL ? 15 : 10;
    int timeHeight = density == Density.FULL ? 19 : 12;
    int volumeHeight = density == Density.FULL ? 35 : 17;
    int gap = 4;
    int plotTop = margin + headerHeight + gap;
    int timeTop = height - margin - timeHeight;
    int volumeTop = timeTop - gap - volumeHeight;
    int plotBottom = volumeTop - gap - 1;
    int priceLeft = width - margin - axisWidth;
    Rect header = new Rect(margin, margin, width - margin * 2, headerHeight);
    Rect plot = new Rect(margin, plotTop, priceLeft - gap - margin,
        plotBottom - plotTop + 1);
    Rect priceAxis = new Rect(priceLeft, plotTop, axisWidth, plot.height());
    Rect volume = new Rect(margin, volumeTop, plot.width(), volumeHeight);
    Rect timeAxis = new Rect(margin, timeTop, width - margin * 2, timeHeight);
    return new MarketChartLayout(density, header, plot, priceAxis, volume, timeAxis);
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
