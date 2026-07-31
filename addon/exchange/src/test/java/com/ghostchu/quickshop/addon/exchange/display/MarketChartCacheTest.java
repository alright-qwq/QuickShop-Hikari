package com.ghostchu.quickshop.addon.exchange.display;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketChartCacheTest {
  @Test
  void reusesImageForIdenticalCompleteKey() {
    MarketChartCache cache = new MarketChartCache(4);
    AtomicInteger renders = new AtomicInteger();
    MarketChartCache.Key key = key("diamond", MarketChartMode.KLINE, "fingerprint-1");

    MarketChartImage first = cache.getOrRender(key, () -> image((byte) renders.incrementAndGet()));
    MarketChartImage second = cache.getOrRender(key, () -> image((byte) renders.incrementAndGet()));

    assertThat(second).isSameAs(first);
    assertThat(renders).hasValue(1);
  }

  @Test
  void redrawsWhenQuoteFingerprintOrModeChanges() {
    MarketChartCache cache = new MarketChartCache(4);
    AtomicInteger renders = new AtomicInteger();

    MarketChartImage first = cache.getOrRender(
        key("diamond", MarketChartMode.KLINE, "fingerprint-1"),
        () -> image((byte) renders.incrementAndGet()));
    MarketChartImage changedQuote = cache.getOrRender(
        key("diamond", MarketChartMode.KLINE, "fingerprint-2"),
        () -> image((byte) renders.incrementAndGet()));
    MarketChartImage changedMode = cache.getOrRender(
        key("diamond", MarketChartMode.LINE, "fingerprint-2"),
        () -> image((byte) renders.incrementAndGet()));

    assertThat(changedQuote).isNotSameAs(first);
    assertThat(changedMode).isNotSameAs(changedQuote);
    assertThat(renders).hasValue(3);
  }

  @Test
  void redrawsWhenTrustedStateOrChartOptionsChange() {
    MarketChartCache cache = new MarketChartCache(4);
    AtomicInteger renders = new AtomicInteger();

    MarketChartCache.Key base = new MarketChartCache.Key("diamond", MarketChartMode.KLINE,
        MarketChartPeriod.ONE_DAY, new MarketChartDimensions(2, 1), "fingerprint-1",
        "options-1", 3L, MarketChartInterval.FIVE_MINUTES);
    MarketChartCache.Key trustedChanged = new MarketChartCache.Key("diamond",
        MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY, new MarketChartDimensions(2, 1),
        "fingerprint-1", "options-1", 4L, MarketChartInterval.FIVE_MINUTES);
    MarketChartCache.Key optionsChanged = new MarketChartCache.Key("diamond",
        MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY, new MarketChartDimensions(2, 1),
        "fingerprint-1", "options-2", 4L, MarketChartInterval.FIFTEEN_MINUTES);

    cache.getOrRender(base, () -> image((byte) renders.incrementAndGet()));
    cache.getOrRender(trustedChanged, () -> image((byte) renders.incrementAndGet()));
    cache.getOrRender(optionsChanged, () -> image((byte) renders.incrementAndGet()));

    assertThat(renders).hasValue(3);
  }

  @Test
  void evictsLeastRecentlyUsedEntryAtCapacity() {
    MarketChartCache cache = new MarketChartCache(2);
    AtomicInteger renders = new AtomicInteger();
    var first = key("first", MarketChartMode.KLINE, "1");
    var second = key("second", MarketChartMode.KLINE, "1");
    var third = key("third", MarketChartMode.KLINE, "1");
    cache.getOrRender(first, () -> image((byte) renders.incrementAndGet()));
    cache.getOrRender(second, () -> image((byte) renders.incrementAndGet()));
    cache.getOrRender(third, () -> image((byte) renders.incrementAndGet()));

    cache.getOrRender(first, () -> image((byte) renders.incrementAndGet()));

    assertThat(renders).hasValue(4);
    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  void closeClearsCacheAndRejectsFurtherRendering() {
    MarketChartCache cache = new MarketChartCache(2);
    cache.getOrRender(key("diamond", MarketChartMode.KLINE, "1"), () -> image((byte) 1));

    cache.close();

    assertThat(cache.size()).isZero();
    assertThatThrownBy(() -> cache.getOrRender(
        key("diamond", MarketChartMode.KLINE, "1"), () -> image((byte) 2)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static MarketChartCache.Key key(String market, MarketChartMode mode, String fingerprint) {
    return new MarketChartCache.Key(market, mode, MarketChartPeriod.ONE_DAY,
        new MarketChartDimensions(2, 1), fingerprint);
  }

  private static MarketChartImage image(byte color) {
    byte[] pixels = new byte[128 * 128];
    java.util.Arrays.fill(pixels, color);
    return new MarketChartImage(128, 128, pixels);
  }
}
