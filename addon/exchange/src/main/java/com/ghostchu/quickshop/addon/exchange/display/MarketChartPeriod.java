package com.ghostchu.quickshop.addon.exchange.display;

import java.time.Duration;
import java.util.Arrays;

public enum MarketChartPeriod {
  ONE_HOUR("1h", Duration.ofHours(1)),
  SIX_HOURS("6h", Duration.ofHours(6)),
  ONE_DAY("24h", Duration.ofHours(24)),
  SEVEN_DAYS("7d", Duration.ofDays(7));

  private final String token;
  private final Duration duration;

  MarketChartPeriod(String token, Duration duration) {
    this.token = token;
    this.duration = duration;
  }

  public String token() {
    return token;
  }

  public Duration duration() {
    return duration;
  }

  public static MarketChartPeriod parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("chart period is required");
    }
    return Arrays.stream(values())
        .filter(period -> period.token.equalsIgnoreCase(value.trim()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unsupported chart period: " + value));
  }
}
