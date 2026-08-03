package com.ghostchu.quickshop.addon.exchange.web;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplaySnapshot;
import com.ghostchu.quickshop.addon.exchange.display.TrustedPricePoint;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PublicMarketCatalogTest {
  @Test
  void exposesStableMarketOrderAndDelegatesBoundedSnapshots() {
    AtomicReference<String> requested = new AtomicReference<>();
    Instant now = Instant.parse("2026-08-01T12:00:00Z");
    PublicMarketCatalog catalog = new PublicMarketCatalog(
        Map.of("diamond-usd", "钻石", "iron-usd", "铁锭"),
        (marketId, period, toExclusive) -> {
          requested.set(marketId + ":" + period.token() + ":" + toExclusive);
          return CompletableFuture.completedFuture(snapshot(marketId, now));
        });

    assertThat(catalog.markets()).extracting(PublicMarketCatalog.Market::marketId)
        .containsExactly("diamond-usd", "iron-usd");
    assertThat(catalog.snapshot("diamond-usd", MarketChartPeriod.ONE_DAY, now).join().marketId())
        .isEqualTo("diamond-usd");
    assertThat(requested).hasValue("diamond-usd:24h:2026-08-01T12:00:00Z");
  }

  @Test
  void acceptsMarketIdsWithSlashesMatchingDefaultConfig() {
    PublicMarketCatalog catalog = new PublicMarketCatalog(
        Map.of("minecraft_diamond/default", "钻石"),
        (marketId, period, toExclusive) -> CompletableFuture.completedFuture(
            snapshot(marketId, Instant.now())));

    assertThat(catalog.markets()).singleElement()
        .extracting(PublicMarketCatalog.Market::marketId)
        .isEqualTo("minecraft_diamond/default");
    assertThat(catalog.contains("minecraft_diamond/default")).isTrue();
  }

  @Test
  void rejectsUnknownOrUnsafeMarketIdsBeforeReading() {
    PublicMarketCatalog catalog = new PublicMarketCatalog(Map.of("diamond-usd", "钻石"),
        (marketId, period, toExclusive) -> CompletableFuture.failedFuture(
            new AssertionError("unexpected read")));

    assertThatIllegalArgumentException().isThrownBy(() ->
        catalog.snapshot("../accounts", MarketChartPeriod.ONE_DAY, Instant.now()));
    assertThatIllegalArgumentException().isThrownBy(() ->
        catalog.snapshot("unknown", MarketChartPeriod.ONE_DAY, Instant.now()));
  }

  static MarketDisplaySnapshot snapshot(String marketId, Instant now) {
    MarketQuote quote = new MarketQuote(marketId, new BigDecimal("102.50"),
        new BigDecimal("101.80"), LiquidityTier.GROWING,
        new BigDecimal("101.50"), new BigDecimal("103.00"),
        new BigDecimal("0.0245"), 328L, new BigDecimal("33281.50"),
        MarketStatus.OPEN, now);
    Candle candle = new Candle(marketId, now.minusSeconds(60),
        new BigDecimal("100.00"), new BigDecimal("103.00"),
        new BigDecimal("99.00"), new BigDecimal("102.50"),
        12L, new BigDecimal("1210.00"));
    return new MarketDisplaySnapshot(marketId, "钻石", quote, List.of(candle),
        List.of(new TrustedPricePoint(now.minusSeconds(30), new BigDecimal("101.80"))),
        LiquidityTier.GROWING, 7L, now.minusSeconds(3600), now);
  }
}
