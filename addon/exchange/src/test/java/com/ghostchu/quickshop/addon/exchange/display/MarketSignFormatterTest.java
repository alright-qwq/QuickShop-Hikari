package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketSignFormatterTest {
  private final MarketSignFormatter formatter = new MarketSignFormatter();

  @Test
  void formatsOpenRisingMarketWithChineseMarketColorAndBidAsk() {
    MarketSignLines lines = formatter.format("钻石市场", quote(
        new BigDecimal("105.25"), new BigDecimal("0.0525"), MarketStatus.OPEN));

    assertThat(lines.lines()).extracting(MarketSignLine::text)
        .containsExactly("钻石市场", "现价 105.25", "+5.25%", "104 / 106");
    assertThat(lines.lines().get(2).tone()).isEqualTo(MarketSignTone.RISE);
  }

  @Test
  void usesGreenForFallingMarketAndGrayForFlatMarket() {
    assertThat(formatter.format("钻石", quote(
        new BigDecimal("95"), new BigDecimal("-0.05"), MarketStatus.OPEN))
        .lines().get(2).tone()).isEqualTo(MarketSignTone.FALL);
    assertThat(formatter.format("钻石", quote(
        new BigDecimal("100"), BigDecimal.ZERO, MarketStatus.OPEN))
        .lines().get(2).tone()).isEqualTo(MarketSignTone.FLAT);
  }

  @Test
  void showsMissingPriceAndMarketStatusInsteadOfBookForUnavailableMarket() {
    MarketQuote quote = new MarketQuote("diamond", null, new BigDecimal("100"), null, null,
        BigDecimal.ZERO, 0, BigDecimal.ZERO, MarketStatus.HALTED,
        Instant.parse("2026-07-30T12:00:00Z"));

    MarketSignLines lines = formatter.format("钻石市场", quote);

    assertThat(lines.lines()).extracting(MarketSignLine::text)
        .containsExactly("钻石市场", "现价 --", "0.00%", "状态 HALTED");
    assertThat(lines.lines().get(3).tone()).isEqualTo(MarketSignTone.STATUS);
  }

  @Test
  void truncatesEveryLineToFifteenCodePoints() {
    MarketSignLines lines = formatter.format("这是一个非常非常长的钻石交易市场名称", quote(
        new BigDecimal("12345678901234567890.12"), new BigDecimal("1.2345"),
        MarketStatus.OPEN));

    assertThat(lines.lines()).allSatisfy(line ->
        assertThat(line.text().codePointCount(0, line.text().length())).isLessThanOrEqualTo(15));
  }

  private static MarketQuote quote(BigDecimal last, BigDecimal change, MarketStatus status) {
    return new MarketQuote("diamond", last, new BigDecimal("100"), new BigDecimal("104"),
        new BigDecimal("106"), change, 20, new BigDecimal("2100"), status,
        Instant.parse("2026-07-30T12:00:00Z"));
  }
}
