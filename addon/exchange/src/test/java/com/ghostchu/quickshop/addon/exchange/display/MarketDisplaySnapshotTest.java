package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDisplaySnapshotTest {
  private final Instant now = Instant.parse("2026-08-01T12:00:00Z");

  @Test
  void fingerprintIgnoresQuoteTimestampButTracksPriceAndCandles() {
    Candle candle = new Candle("diamond", now.minusSeconds(60), new BigDecimal("100"),
        new BigDecimal("106"), new BigDecimal("99"), new BigDecimal("105"), 20,
        new BigDecimal("2100"));

    MarketDisplaySnapshot first = snapshot(now, candle, "105");
    MarketDisplaySnapshot refreshed = snapshot(now.plusSeconds(5), candle, "105");
    MarketDisplaySnapshot repriced = snapshot(now.plusSeconds(5), candle, "106");

    assertThat(refreshed.fingerprint()).isEqualTo(first.fingerprint());
    assertThat(repriced.fingerprint()).isNotEqualTo(first.fingerprint());
  }

  private MarketDisplaySnapshot snapshot(Instant asOf, Candle candle, String lastPrice) {
    MarketQuote quote = new MarketQuote("diamond", new BigDecimal(lastPrice),
        new BigDecimal("100"), LiquidityTier.STABLE, new BigDecimal("104"),
        new BigDecimal("106"), new BigDecimal("0.05"), 20, new BigDecimal("2100"),
        MarketStatus.OPEN, asOf);
    return new MarketDisplaySnapshot("diamond", "钻石市场", quote, List.of(candle),
        now.minusSeconds(24 * 60 * 60), now.plusSeconds(1));
  }
}
