package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.math.BigDecimal;

/** Immutable market-list value consumed by TNML page rendering only. */
public record MarketRow(String marketId, String displayName, BigDecimal lastPrice,
                        BigDecimal bestBid, BigDecimal bestAsk, BigDecimal change24h,
                        long volume24h, MarketStatus status) {
}
