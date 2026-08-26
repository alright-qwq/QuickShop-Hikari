package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Background-only read facade used by exchange UI pages. */
public final class ExchangeViewService {
  private final Map<String, MarketView> markets;
  private final MarketDataService marketData;
  private final Executor executor;
  private final ExchangeRepository repository;
  private final List<TransferTarget> transferTargets;
  private final MarketListPresenter presenter = new MarketListPresenter();

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor) {
    this(markets, marketData, executor, null, List.of());
  }

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor, ExchangeRepository repository) {
    this(markets, marketData, executor, repository, List.of());
  }

  public ExchangeViewService(Map<String, MarketView> markets, MarketDataService marketData,
                             Executor executor, ExchangeRepository repository,
                             List<TransferTarget> transferTargets) {
    this.markets = Map.copyOf(new LinkedHashMap<>(markets));
    this.marketData = Objects.requireNonNull(marketData, "marketData");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.repository = repository;
    this.transferTargets = List.copyOf(Objects.requireNonNull(transferTargets, "transferTargets"));
  }

  public List<TransferTarget> transferTargets() {
    return transferTargets;
  }

  public void subscribeMarketUpdates(UUID playerId, Consumer<MarketDataService.PlayerUpdate> consumer) {
    marketData.subscribePlayer(playerId, consumer);
  }

  public void unsubscribeMarketUpdates(UUID playerId) {
    marketData.unsubscribePlayer(playerId);
  }

  public CompletableFuture<List<MarketRow>> marketRows() {
    return marketList().thenApply(MarketListSnapshot::markets);
  }

  public CompletableFuture<MarketListSnapshot> marketList() {
    return CompletableFuture.supplyAsync(() -> {
      List<MarketListPresenter.Entry> entries = loadMarketEntries();
      return new MarketListSnapshot(presenter.rows(entries), presenter.overview(entries));
    }, executor);
  }

  public CompletableFuture<MarketOverviewSnapshot> marketOverview() {
    return marketList().thenApply(MarketListSnapshot::overview);
  }

  public CompletableFuture<MarketRow> marketRow(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    MarketView market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return presenter.rows(List.of(new MarketListPresenter.Entry(market.marketId(),
            market.displayName(), market.service().marketQuote(marketData),
            market.assetType(), market.symbol(), market.totalSupply(),
            market.securityStatus().get()))).getFirst();
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market quote: " + marketId, failure);
      }
    }, executor);
  }

  public String resolveMarketIdBySymbol(String symbol) {
    if (symbol == null || symbol.isBlank()) {
      return null;
    }
    for (MarketView market : markets.values()) {
      if (market.symbol() != null && market.symbol().equalsIgnoreCase(symbol)) {
        return market.marketId();
      }
    }
    return null;
  }

  /** All configured security symbols, sorted, for tab completion. */
  public List<String> securitySymbols() {
    return markets.values().stream()
        .map(MarketView::symbol)
        .filter(Objects::nonNull)
        .distinct()
        .sorted()
        .toList();
  }

  /** Returns the latest market quote for a market id, or null when the market is unknown. */
  public MarketQuote marketQuote(String marketId) {
    MarketView market = markets.get(marketId);
    if (market == null) {
      return null;
    }
    try {
      return market.service().marketQuote(marketData);
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load market quote: " + marketId, failure);
    }
  }

  /** Returns the configured market display name, or the market id when unknown. */
  public String marketDisplayName(String marketId) {
    MarketView market = markets.get(marketId);
    return market == null ? marketId : market.displayName();
  }

  public CompletableFuture<MarketDashboardSnapshot> marketDashboard(String marketId) {
    return marketDashboard(marketId, Duration.ofMinutes(9));
  }

  public CompletableFuture<MarketDashboardSnapshot> marketDashboard(
      String marketId, Duration candleWindow) {
    MarketView market = requiredMarket(marketId);
    Duration window = candleWindow == null || candleWindow.isZero() || candleWindow.isNegative()
        ? Duration.ofMinutes(9) : candleWindow;
    return CompletableFuture.supplyAsync(() -> {
      try {
        PersistentOrderService.MarketBookSnapshot book = market.service()
            .marketBookSnapshot(marketData, 5);
        MarketQuote quote = marketData.quote(marketId, book.referencePrice(), book.bestBid(),
            book.bestAsk(), book.status(), book.asOf());
        MarketRow row = presenter.rows(List.of(new MarketListPresenter.Entry(market.marketId(),
            market.displayName(), quote, market.assetType(), market.symbol(),
            market.totalSupply(), market.securityStatus().get()))).getFirst();
        Instant asOf = book.asOf();
        List<com.ghostchu.quickshop.addon.exchange.marketdata.Candle> candles =
            marketData.recentCandles(marketId, asOf.minus(window),
                asOf.plusSeconds(60));
        BigDecimal spread = spread(quote.bestBid(), quote.bestAsk());
        return new MarketDashboardSnapshot(row, candles, book.bids(), book.asks(), spread,
            spreadPercent(spread, quote.bestBid(), quote.bestAsk()), quote.notional24h());
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market dashboard: " + marketId, failure);
      }
    }, executor);
  }

  public CompletableFuture<List<Order>> accountOrders(UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account order page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountOpenOrders(accountId, limit, offset).stream()
            .map(com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder::order)
            .toList();
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account orders", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<AccountAssetBalance>> accountAssets(UUID accountId) {
    Objects.requireNonNull(accountId, "accountId");
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountAssets(accountId);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account assets", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<Trade>> accountTrades(UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account trade page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountTrades(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account trades", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<TransferRecord>> accountTransfers(
      UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account transfer page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountTransfers(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account transfers", failure);
      }
    }, executor);
  }

  public CompletableFuture<List<AccountLedgerEntry>> accountLedger(
      UUID accountId, int limit, int offset) {
    if (accountId == null || limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account ledger page");
    }
    if (repository == null) {
      return CompletableFuture.failedFuture(new IllegalStateException("account views are not configured"));
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return repository.accountLedgerEntries(accountId, limit, offset);
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load account ledger", failure);
      }
    }, executor);
  }

  private List<MarketListPresenter.Entry> loadMarketEntries() {
    List<MarketListPresenter.Entry> entries = new ArrayList<>();
    for (MarketView market : markets.values()) {
      try {
        String securityStatus = market.assetType() != null
            && "VIRTUAL_SECURITY".equals(market.assetType())
            ? market.securityStatus().get() : null;
        entries.add(new MarketListPresenter.Entry(market.marketId(), market.displayName(),
            market.service().marketQuote(marketData), market.assetType(), market.symbol(),
            market.totalSupply(), securityStatus));
      } catch (SQLException failure) {
        throw new IllegalStateException("failed to load market quote: " + market.marketId(), failure);
      }
    }
    return List.copyOf(entries);
  }

  private MarketView requiredMarket(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("marketId is required");
    }
    MarketView market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return market;
  }

  private static BigDecimal spread(BigDecimal bid, BigDecimal ask) {
    return bid == null || ask == null ? null : ask.subtract(bid);
  }

  private static BigDecimal spreadPercent(BigDecimal spread, BigDecimal bid, BigDecimal ask) {
    if (spread == null) {
      return null;
    }
    BigDecimal midpoint = bid.add(ask).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    return midpoint.signum() == 0 ? null : spread.divide(midpoint, 8, RoundingMode.HALF_UP);
  }

  public record MarketView(String marketId, String displayName, PersistentOrderService service,
                           String assetType, String symbol, Long totalSupply,
                           Supplier<String> securityStatus) {
    public MarketView(String marketId, String displayName, PersistentOrderService service) {
      this(marketId, displayName, service, null, null, null, () -> null);
    }

    public MarketView {
      if (marketId == null || marketId.isBlank() || displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("market display data is required");
      }
      Objects.requireNonNull(service, "service");
      Objects.requireNonNull(securityStatus, "securityStatus");
    }
  }
}
