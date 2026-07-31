package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Objects;

public record MarketSignLine(String text, MarketSignTone tone) {
  public MarketSignLine {
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(tone, "tone");
  }
}
