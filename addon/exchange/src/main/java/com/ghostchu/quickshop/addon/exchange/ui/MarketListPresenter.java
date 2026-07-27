package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.util.List;
import java.util.Objects;

/** Maps quote values to page-safe rows without exposing books or repositories to the UI. */
public final class MarketListPresenter {
  public List<MarketRow> rows(List<Entry> entries) {
    return List.copyOf(entries.stream().map(entry -> {
      MarketQuote quote = entry.quote();
      return new MarketRow(entry.marketId(), entry.displayName(), quote.lastPrice(),
          quote.bestBid(), quote.bestAsk(), quote.change24h(), quote.volume24h(), quote.status());
    }).toList());
  }

  public record Entry(String marketId, String displayName, MarketQuote quote) {
    public Entry {
      if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display data is required");
      }
      Objects.requireNonNull(quote, "quote");
    }
  }
}
