package com.ghostchu.quickshop.addon.exchange.service;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.risk.CircuitBreaker;
import com.ghostchu.quickshop.addon.exchange.core.risk.ReferencePriceTracker;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import java.util.List;

public record RecoveredMarket(
    OrderBook book, ReferencePriceTracker referencePrices,
    TrustedPriceState trustedPriceState, List<TradeInfluence> recentInfluences,
    CircuitBreaker circuitBreaker, MarketState state) {
  public RecoveredMarket {
    recentInfluences = List.copyOf(recentInfluences);
  }

  public long prioritySequence() {
    return state.prioritySequence();
  }

  public long matchSequence() {
    return state.matchSequence();
  }

  public long marketVersion() {
    return state.version();
  }
}
