package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDisplayServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

  @Test
  void rendersAndSchedulesEveryMapTileInBindingOrder() throws Exception {
    RecordingScheduler scheduler = new RecordingScheduler();
    MarketDisplayService service = service(completedSource(), scheduler);
    MapWallBinding wall = wall();

    service.refresh(wall).get(5, TimeUnit.SECONDS);

    assertThat(scheduler.frames).containsExactlyElementsOf(wall.frames());
    assertThat(scheduler.images).hasSize(2).allSatisfy(image -> {
      assertThat(image.width()).isEqualTo(128);
      assertThat(image.height()).isEqualTo(128);
    });
  }

  @Test
  void formatsAndSchedulesSignUpdate() throws Exception {
    RecordingScheduler scheduler = new RecordingScheduler();
    MarketDisplayService service = service(completedSource(), scheduler);
    MarketSignBinding sign = new MarketSignBinding(UUID.randomUUID(), "diamond",
        new DisplayLocation(UUID.randomUUID(), 1, 64, 2), MarketSignFormat.DEFAULT);

    service.refresh(sign).get(5, TimeUnit.SECONDS);

    assertThat(scheduler.signs).containsExactly(sign);
    assertThat(scheduler.signLines.getFirst().lines()).extracting(MarketSignLine::text)
        .containsExactly("钻石市场", "现价 105", "+5.00%", "104 / 106");
  }

  @Test
  void coalescesConcurrentRefreshIntoOneFollowUpRefresh() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    CompletableFuture<MarketDisplaySnapshot> first = new CompletableFuture<>();
    MarketDisplayDataSource source = (market, period, now) ->
        reads.incrementAndGet() == 1 ? first : CompletableFuture.completedFuture(snapshot());
    RecordingScheduler scheduler = new RecordingScheduler();
    MarketDisplayService service = service(source, scheduler);
    MapWallBinding wall = wall();

    CompletableFuture<Void> initial = service.refresh(wall);
    CompletableFuture<Void> coalesced = service.refresh(wall);
    assertThat(reads).hasValue(1);
    assertThat(coalesced).isSameAs(initial);

    first.complete(snapshot());
    initial.get(5, TimeUnit.SECONDS);
    waitFor(() -> reads.get() == 2);
    assertThat(reads).hasValue(2);
  }

  @Test
  void closeCancelsAcceptedRefreshesBeforeWorldUpdates() {
    CompletableFuture<MarketDisplaySnapshot> snapshot = new CompletableFuture<>();
    RecordingScheduler scheduler = new RecordingScheduler();
    MarketDisplayService service = service((market, period, now) -> snapshot, scheduler);

    CompletableFuture<Void> refresh = service.refresh(wall());
    service.close();
    snapshot.complete(snapshot());

    assertThat(refresh).isCompletedExceptionally();
    assertThat(scheduler.frames).isEmpty();
  }

  @Test
  void closeRejectsNewRefreshesAndClearsCache() {
    RecordingScheduler scheduler = new RecordingScheduler();
    MarketDisplayService service = service(completedSource(), scheduler);

    service.close();

    assertThatThrownBy(() -> service.refresh(wall()))
        .isInstanceOf(IllegalStateException.class);
  }

  private static MarketDisplayService service(MarketDisplayDataSource source,
                                               DisplayScheduler scheduler) {
    return new MarketDisplayService(source, new MarketChartSeriesBuilder(),
        new MarketChartRenderer(), new MarketChartCache(16), new MarketSignFormatter(), scheduler,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static MarketDisplayDataSource completedSource() {
    return (market, period, now) -> CompletableFuture.completedFuture(snapshot());
  }

  private static MarketDisplaySnapshot snapshot() {
    Candle candle = new Candle("diamond", NOW.minusSeconds(60), new BigDecimal("100"),
        new BigDecimal("106"), new BigDecimal("99"), new BigDecimal("105"), 20,
        new BigDecimal("2100"));
    MarketQuote quote = new MarketQuote("diamond", new BigDecimal("105"),
        new BigDecimal("100"), new BigDecimal("104"), new BigDecimal("106"),
        new BigDecimal("0.05"), 20, new BigDecimal("2100"), MarketStatus.OPEN, NOW);
    return new MarketDisplaySnapshot("diamond", "钻石市场", quote, List.of(candle),
        NOW.minusSeconds(24 * 60 * 60), NOW);
  }

  private static MapWallBinding wall() {
    UUID world = UUID.randomUUID();
    return new MapWallBinding(UUID.randomUUID(), "diamond", MarketChartMode.KLINE,
        MarketChartPeriod.ONE_DAY, new MarketChartDimensions(2, 1), List.of(
            new MapFrameBinding(UUID.randomUUID(), world, 1, 64, 2, 7),
            new MapFrameBinding(UUID.randomUUID(), world, 2, 64, 2, 8)));
  }

  private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static final class RecordingScheduler implements DisplayScheduler {
    private final List<MapFrameBinding> frames = new ArrayList<>();
    private final List<MarketChartImage> images = new ArrayList<>();
    private final List<MarketSignBinding> signs = new ArrayList<>();
    private final List<MarketSignLines> signLines = new ArrayList<>();

    @Override
    public CompletableFuture<Void> updateMapFrame(MapFrameBinding frame, MarketChartImage image) {
      frames.add(frame);
      images.add(image);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> updateSign(MarketSignBinding sign, MarketSignLines lines) {
      signs.add(sign);
      signLines.add(lines);
      return CompletableFuture.completedFuture(null);
    }
  }
}
