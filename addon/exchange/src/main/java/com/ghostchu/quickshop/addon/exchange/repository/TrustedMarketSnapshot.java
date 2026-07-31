package com.ghostchu.quickshop.addon.exchange.repository;

import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceAdjustment;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import java.util.List;

/** Durable trusted state plus the bounded event windows required for recovery. */
public record TrustedMarketSnapshot(
    TrustedPriceState state, List<TradeInfluence> influences,
    List<TrustedPriceAdjustment> adjustments) {

  public TrustedMarketSnapshot {
    influences = List.copyOf(influences);
    adjustments = List.copyOf(adjustments);
  }
}
