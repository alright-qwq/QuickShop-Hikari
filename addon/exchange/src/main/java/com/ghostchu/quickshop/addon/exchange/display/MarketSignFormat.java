package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Locale;

public enum MarketSignFormat {
  DEFAULT;

  public static MarketSignFormat parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sign format is required");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("unsupported sign format: " + value, invalid);
    }
  }
}
