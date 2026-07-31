package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public final class MarketSignFormatter {
  private static final int MAX_CODE_POINTS = 15;

  public MarketSignLines format(String displayName, MarketQuote quote) {
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName is required");
    }
    Objects.requireNonNull(quote, "quote");
    MarketSignLine change = changeLine(quote.change24h());
    MarketSignLine fourth = quote.status() == MarketStatus.OPEN
        ? new MarketSignLine(truncate(price(quote.bestBid()) + " / " + price(quote.bestAsk())),
            MarketSignTone.NORMAL)
        : new MarketSignLine(truncate("状态 " + quote.status()), MarketSignTone.STATUS);
    return new MarketSignLines(List.of(
        new MarketSignLine(truncate(displayName), MarketSignTone.NORMAL),
        new MarketSignLine(truncate("现价 " + price(quote.lastPrice())), MarketSignTone.NORMAL),
        change,
        fourth));
  }

  private static MarketSignLine changeLine(BigDecimal change) {
    int direction = change.signum();
    BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
        .setScale(2, RoundingMode.HALF_UP);
    String prefix = direction > 0 ? "+" : "";
    MarketSignTone tone = direction > 0 ? MarketSignTone.RISE
        : direction < 0 ? MarketSignTone.FALL : MarketSignTone.FLAT;
    return new MarketSignLine(truncate(prefix + percent.toPlainString() + "%"), tone);
  }

  private static String price(BigDecimal value) {
    return value == null ? "--" : value.stripTrailingZeros().toPlainString();
  }

  private static String truncate(String value) {
    int count = value.codePointCount(0, value.length());
    if (count <= MAX_CODE_POINTS) {
      return value;
    }
    int end = value.offsetByCodePoints(0, MAX_CODE_POINTS);
    return value.substring(0, end);
  }
}
