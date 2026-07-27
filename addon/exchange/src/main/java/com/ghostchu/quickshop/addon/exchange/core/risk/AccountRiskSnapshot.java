package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.util.Objects;

public record AccountRiskSnapshot(long holding, BigDecimal frozenCurrency, int openOrders) {
  public AccountRiskSnapshot {
    if (holding < 0 || openOrders < 0) {
      throw new IllegalArgumentException("account exposure cannot be negative");
    }
    frozenCurrency = Objects.requireNonNull(frozenCurrency, "frozenCurrency");
    if (frozenCurrency.signum() < 0) {
      throw new IllegalArgumentException("frozenCurrency cannot be negative");
    }
  }

  public boolean canAddHolding(long added, long maximum) {
    return added >= 0 && maximum >= 0 && holding <= maximum - added;
  }

  public boolean canFreeze(BigDecimal added, BigDecimal maximum) {
    return added != null && maximum != null && added.signum() >= 0
        && frozenCurrency.add(added).compareTo(maximum) <= 0;
  }

  public boolean canOpenOrder(int maximum) {
    return maximum >= 0 && openOrders < maximum;
  }
}
