package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleSeries;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Converts market snapshot data to fixed-size, inventory-safe chart rows. */
public final class MarketDashboardPresenter {
  static final int DEPTH_ROWS = 5;
  static final int CANDLE_ROWS = 9;
  static final int MAX_STRENGTH = 8;

  public DashboardRows present(MarketDashboardSnapshot snapshot) {
    return present(snapshot, Duration.ofMinutes(1));
  }

  public DashboardRows present(MarketDashboardSnapshot snapshot, Duration timeframe) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(timeframe, "timeframe");
    List<Candle> candles = timeframe.getSeconds() <= 60
        ? snapshot.recentCandles()
        : CandleSeries.aggregate(snapshot.recentCandles(), timeframe);
    return new DashboardRows(depthRows(snapshot.bids(), Comparator.reverseOrder()),
        depthRows(snapshot.asks(), Comparator.naturalOrder()), candleRows(candles));
  }

  private static List<DepthRow> depthRows(List<MarketDataService.DepthLevel> levels,
                                          Comparator<BigDecimal> priceOrder) {
    List<MarketDataService.DepthLevel> visible = levels.stream()
        .sorted(Comparator.comparing(MarketDataService.DepthLevel::price, priceOrder))
        .limit(DEPTH_ROWS).toList();
    long maximumQuantity = visible.stream().mapToLong(MarketDataService.DepthLevel::quantity)
        .max().orElse(0L);
    ArrayList<DepthRow> rows = new ArrayList<>(DEPTH_ROWS);
    long cumulative = 0L;
    for (MarketDataService.DepthLevel level : visible) {
      cumulative = Math.addExact(cumulative, level.quantity());
      rows.add(new DepthRow(level.price(), level.quantity(), cumulative, level.executable(),
          strength(level.quantity(), maximumQuantity), false));
    }
    while (rows.size() < DEPTH_ROWS) {
      rows.add(DepthRow.emptyRow());
    }
    return List.copyOf(rows);
  }

  private static List<CandleRow> candleRows(List<Candle> candles) {
    List<Candle> visible = candles.stream().sorted(Comparator.comparing(Candle::bucketStart)).toList();
    if (visible.size() > CANDLE_ROWS) {
      visible = visible.subList(visible.size() - CANDLE_ROWS, visible.size());
    }
    if (visible.size() < 2) {
      return java.util.Collections.nCopies(CANDLE_ROWS, CandleRow.emptyRow());
    }
    long maximumVolume = visible.stream().mapToLong(Candle::volume).max().orElse(0L);
    ArrayList<CandleRow> rows = new ArrayList<>(CANDLE_ROWS);
    while (rows.size() + visible.size() < CANDLE_ROWS) {
      rows.add(CandleRow.emptyRow());
    }
    for (Candle candle : visible) {
      rows.add(new CandleRow(candle, CandleDirection.from(candle),
          strength(candle.volume(), maximumVolume), false));
    }
    return List.copyOf(rows);
  }

  private static int strength(long value, long maximum) {
    if (value <= 0 || maximum <= 0) {
      return 0;
    }
    return Math.max(1, (int) Math.ceil(value * (double) MAX_STRENGTH / maximum));
  }

  public record DashboardRows(List<DepthRow> bids, List<DepthRow> asks,
                              List<CandleRow> candles) {
    public DashboardRows {
      bids = List.copyOf(bids);
      asks = List.copyOf(asks);
      candles = List.copyOf(candles);
    }
  }

  public record DepthRow(BigDecimal price, long quantity, long cumulativeQuantity,
                         boolean executable, int strength, boolean empty) {
    static DepthRow emptyRow() {
      return new DepthRow(null, 0, 0, false, 0, true);
    }
  }

  public record CandleRow(Candle candle, CandleDirection direction, int strength, boolean empty) {
    static CandleRow emptyRow() {
      return new CandleRow(null, CandleDirection.FLAT, 0, true);
    }
  }

  public enum CandleDirection {
    UP, DOWN, FLAT;

    static CandleDirection from(Candle candle) {
      int comparison = candle.close().compareTo(candle.open());
      return comparison > 0 ? UP : comparison < 0 ? DOWN : FLAT;
    }
  }
}
