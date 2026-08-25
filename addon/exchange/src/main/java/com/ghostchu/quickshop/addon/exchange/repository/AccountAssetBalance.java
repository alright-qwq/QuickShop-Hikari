package com.ghostchu.quickshop.addon.exchange.repository;

import java.math.BigDecimal;

/** Read-only account asset projection for player views. */
public record AccountAssetBalance(
    String kind, String assetId, BigDecimal available, BigDecimal frozen) {
  public AccountAssetBalance {
    if ((kind == null || (!kind.equals("currency") && !kind.equals("item")))
        || assetId == null || assetId.isBlank() || available == null || frozen == null
        || available.signum() < 0 || frozen.signum() < 0) {
      throw new IllegalArgumentException("invalid account asset balance");
    }
  }
}
