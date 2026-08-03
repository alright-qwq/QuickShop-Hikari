package com.ghostchu.quickshop.addon.exchange.web;

import com.ghostchu.quickshop.addon.exchange.display.MarketDisplaySnapshot;
import com.ghostchu.quickshop.addon.exchange.display.TrustedPricePoint;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Dependency-free JSON encoder for the deliberately small public market contract. */
public final class PublicMarketJson {
  private PublicMarketJson() {}

  public static String markets(List<PublicMarketCatalog.Market> markets) {
    Objects.requireNonNull(markets, "markets");
    StringBuilder json = new StringBuilder("{\"markets\":[");
    for (int index = 0; index < markets.size(); index++) {
      if (index > 0) json.append(',');
      PublicMarketCatalog.Market market = markets.get(index);
      json.append("{\"marketId\":").append(string(market.marketId()))
          .append(",\"displayName\":").append(string(market.displayName())).append('}');
    }
    return json.append("]}").toString();
  }

  public static String snapshot(MarketDisplaySnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    MarketQuote quote = snapshot.quote();
    StringBuilder json = new StringBuilder("{")
        .append("\"marketId\":").append(string(snapshot.marketId()))
        .append(",\"displayName\":").append(string(snapshot.displayName()))
        .append(",\"lastPrice\":").append(decimal(quote.lastPrice()))
        .append(",\"trustedPrice\":").append(decimal(quote.referencePrice()))
        .append(",\"bestBid\":").append(decimal(quote.bestBid()))
        .append(",\"bestAsk\":").append(decimal(quote.bestAsk()))
        .append(",\"change24h\":").append(decimal(quote.change24h()))
        .append(",\"volume24h\":").append(quote.volume24h())
        .append(",\"notional24h\":").append(decimal(quote.notional24h()))
        .append(",\"status\":").append(string(quote.status().name()))
        .append(",\"liquidityTier\":").append(string(snapshot.liquidityTier().name()))
        .append(",\"asOf\":").append(string(quote.asOf().toString()))
        .append(",\"fromInclusive\":").append(string(snapshot.fromInclusive().toString()))
        .append(",\"toExclusive\":").append(string(snapshot.toExclusive().toString()))
        .append(",\"candles\":[");
    appendCandles(json, snapshot.candles());
    json.append("],\"trustedPoints\":[");
    appendTrustedPoints(json, snapshot.trustedPoints());
    return json.append("]}").toString();
  }

  public static String health(boolean ready, int markets, java.time.Instant at) {
    return "{\"status\":" + string(ready ? "ok" : "starting")
        + ",\"markets\":" + markets + ",\"asOf\":" + string(at.toString()) + "}";
  }

  public static String error(String code, String message) {
    return "{\"error\":" + string(code) + ",\"message\":" + string(message) + "}";
  }

  private static void appendCandles(StringBuilder json, List<Candle> candles) {
    for (int index = 0; index < candles.size(); index++) {
      if (index > 0) json.append(',');
      Candle candle = candles.get(index);
      json.append("{\"at\":").append(string(candle.bucketStart().toString()))
          .append(",\"open\":").append(decimal(candle.open()))
          .append(",\"high\":").append(decimal(candle.high()))
          .append(",\"low\":").append(decimal(candle.low()))
          .append(",\"close\":").append(decimal(candle.close()))
          .append(",\"volume\":").append(candle.volume())
          .append(",\"notional\":").append(decimal(candle.notional())).append('}');
    }
  }

  private static void appendTrustedPoints(StringBuilder json, List<TrustedPricePoint> points) {
    for (int index = 0; index < points.size(); index++) {
      if (index > 0) json.append(',');
      TrustedPricePoint point = points.get(index);
      json.append("{\"at\":").append(string(point.at().toString()))
          .append(",\"price\":").append(decimal(point.price())).append('}');
    }
  }

  private static String decimal(BigDecimal value) {
    return value == null ? "null" : string(value.toPlainString());
  }

  static String string(String value) {
    Objects.requireNonNull(value, "json string");
    StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.append('"').toString();
  }
}
