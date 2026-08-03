package com.ghostchu.quickshop.addon.exchange.web;

import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplayDataSource;
import com.ghostchu.quickshop.addon.exchange.display.MarketDisplaySnapshot;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Immutable whitelist of markets exposed by the public read-only site. */
public final class PublicMarketCatalog {
  private static final Pattern SAFE_MARKET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");
  private final Map<String, String> displayNames;
  private final List<Market> markets;
  private final MarketDisplayDataSource dataSource;

  public PublicMarketCatalog(Map<String, String> displayNames, MarketDisplayDataSource dataSource) {
    Objects.requireNonNull(displayNames, "displayNames");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    if (displayNames.isEmpty()) {
      throw new IllegalArgumentException("at least one public market is required");
    }
    java.util.LinkedHashMap<String, String> validated = new java.util.LinkedHashMap<>();
    displayNames.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
      String marketId = requireSafeMarketId(entry.getKey());
      String displayName = Objects.requireNonNull(entry.getValue(), "displayName").trim();
      if (displayName.isEmpty() || displayName.length() > 80) {
        throw new IllegalArgumentException("invalid public market display name: " + marketId);
      }
      validated.put(marketId, displayName);
    });
    this.displayNames = Map.copyOf(validated);
    this.markets = validated.entrySet().stream()
        .map(entry -> new Market(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(Market::marketId)).toList();
  }

  public List<Market> markets() {
    return markets;
  }

  public CompletableFuture<MarketDisplaySnapshot> snapshot(
      String marketId, MarketChartPeriod period, Instant toExclusive) {
    String safeId = requireSafeMarketId(marketId);
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(toExclusive, "toExclusive");
    if (!displayNames.containsKey(safeId)) {
      throw new IllegalArgumentException("unknown public market: " + safeId);
    }
    return dataSource.snapshot(safeId, period, toExclusive);
  }

  public boolean contains(String marketId) {
    return marketId != null && SAFE_MARKET_ID.matcher(marketId).matches()
        && displayNames.containsKey(marketId);
  }

  private static String requireSafeMarketId(String marketId) {
    if (marketId == null || !SAFE_MARKET_ID.matcher(marketId).matches()) {
      throw new IllegalArgumentException("invalid public market id");
    }
    return marketId;
  }

  public record Market(String marketId, String displayName) {
    public Market {
      requireSafeMarketId(marketId);
      if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("public market display name is required");
      }
    }
  }
}
