package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Locale;

public record MarketChartDimensions(int columns, int rows) {
  public static final int MAP_SIZE = 128;

  public MarketChartDimensions {
    boolean supported = columns == 1 && rows == 1
        || columns == 2 && rows == 1
        || columns == 2 && rows == 2;
    if (!supported) {
      throw new IllegalArgumentException("supported chart dimensions are 1x1, 2x1 and 2x2");
    }
  }

  public int pixelWidth() {
    return Math.multiplyExact(columns, MAP_SIZE);
  }

  public int pixelHeight() {
    return Math.multiplyExact(rows, MAP_SIZE);
  }

  public static MarketChartDimensions parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("chart dimensions are required");
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "1x1" -> new MarketChartDimensions(1, 1);
      case "2x1" -> new MarketChartDimensions(2, 1);
      case "2x2" -> new MarketChartDimensions(2, 2);
      default -> throw new IllegalArgumentException("unsupported chart dimensions: " + value);
    };
  }
}
