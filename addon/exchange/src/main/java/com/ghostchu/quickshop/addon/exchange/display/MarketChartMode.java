package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Locale;

public enum MarketChartMode {
  KLINE,
  LINE;

  public static MarketChartMode parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("chart mode is required");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("unsupported chart mode: " + value, invalid);
    }
  }
}
