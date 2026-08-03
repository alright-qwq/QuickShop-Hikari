package com.ghostchu.quickshop.addon.exchange.web;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicMarketJsonTest {
  @Test
  void serializesPublicSnapshotWithoutPrivateTradingData() {
    String json = PublicMarketJson.snapshot(
        PublicMarketCatalogTest.snapshot("diamond-usd", Instant.parse("2026-08-01T12:00:00Z")));

    assertThat(json)
        .contains("\"marketId\":\"diamond-usd\"")
        .contains("\"displayName\":\"钻石\"")
        .contains("\"lastPrice\":\"102.50\"")
        .contains("\"trustedPrice\":\"101.80\"")
        .contains("\"bestBid\":\"101.50\"")
        .contains("\"bestAsk\":\"103.00\"")
        .contains("\"status\":\"OPEN\"")
        .contains("\"candles\":[")
        .contains("\"trustedPoints\":[")
        .doesNotContain("accountId", "player", "orderId", "audit", "pairKey");
  }

  @Test
  void escapesMarketLabelsAndUsesNullForMissingPrices() {
    String markets = PublicMarketJson.markets(List.of(
        new PublicMarketCatalog.Market("book-usd", "书籍 \"精选\"")));

    assertThat(markets).isEqualTo(
        "{\"markets\":[{\"marketId\":\"book-usd\",\"displayName\":\"书籍 \\\"精选\\\"\"}]}");
    assertThat(PublicMarketJson.error("not_found", "unknown\nmarket"))
        .isEqualTo("{\"error\":\"not_found\",\"message\":\"unknown\\nmarket\"}");
  }
}
