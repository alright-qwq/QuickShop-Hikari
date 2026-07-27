package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Background-only read facade used by exchange UI pages. */
public final class ExchangeViewService {
  private final Map<String, MarketView> markets;
  private final MarketDataService marketData;
  private final Executor executor;
  private final MarketListPresenter presenter = new MarketListPresenter();

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor) {
    this.markets = Map.copyOf(new LinkedHashMap<>(markets));
    this.marketData = Objects.requireNonNull(marketData, "marketData");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public CompletableFuture<List<MarketRow>> marketRows() {
    return CompletableFuture.supplyAsync(() -> {
      List<MarketListPresenter.Entry> entries = new ArrayList<>();
      for (MarketView market : markets.values()) {
        try {
          entries.add(new MarketListPresenter.Entry(market.marketId(), market.displayName(),
              market.service().marketQuote(marketData)));
        } catch (SQLException failure) {
          throw new IllegalStateException("failed to load market quote: " + market.marketId(), failure);
        }
      }
      return presenter.rows(entries);
    }, executor);
  }

  public record MarketView(String marketId, String displayName, PersistentOrderService service) {
    public MarketView {
      if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display data is required");
      }
      Objects.requireNonNull(service, "service");
    }
  }
}
