package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.math.BigDecimal;
import java.time.Instant;

public record PriceSample(BigDecimal price, long quantity, Instant occurredAt) {}
