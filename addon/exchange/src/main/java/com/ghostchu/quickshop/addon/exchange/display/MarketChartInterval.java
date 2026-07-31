package com.ghostchu.quickshop.addon.exchange.display;

import java.time.Duration;

public enum MarketChartInterval {
  FIVE_MINUTES("5m", Duration.ofMinutes(5)),
  FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
  ONE_HOUR("1h", Duration.ofHours(1)),
  SIX_HOURS("6h", Duration.ofHours(6)),
  ONE_DAY("24h", Duration.ofDays(1));

  private final String label;
  private final Duration duration;

  MarketChartInterval(String label, Duration duration) {
    this.label = label;
    this.duration = duration;
  }

  public String label() {
    return label;
  }

  public Duration duration() {
    return duration;
  }
}
