package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketQuoteTest {
  @Test
  void rejectsNegativeRollingVolume() {
    assertThatThrownBy(() -> new MarketQuote("diamond-usd", null, new BigDecimal("100.00"),
        null, null, BigDecimal.ZERO, -1, BigDecimal.ZERO, MarketStatus.OPEN, Instant.EPOCH))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
