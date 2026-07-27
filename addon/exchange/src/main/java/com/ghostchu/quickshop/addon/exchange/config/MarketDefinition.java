package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import java.util.Objects;

public record MarketDefinition(
    String marketId, String displayName, boolean enabled,
    ItemDefinition item, StructuralRules structural, RiskRules risk,
    boolean blockContainerShops) {
  public MarketDefinition {
    requireText(marketId, "marketId");
    requireText(displayName, "displayName");
    Objects.requireNonNull(item, "item");
    Objects.requireNonNull(structural, "structural");
    Objects.requireNonNull(risk, "risk");
  }

  public record ItemDefinition(FingerprintMode mode, String material,
                               String encodedTemplate, String fingerprint) {
    public ItemDefinition {
      Objects.requireNonNull(mode, "mode");
      requireText(material, "material");
      if (mode == FingerprintMode.STRICT
          && (isBlank(encodedTemplate) || isBlank(fingerprint))) {
        throw new IllegalArgumentException("STRICT market requires template and fingerprint");
      }
    }
  }

  public record StructuralRules(
      String currencyId, BigDecimal basePrice, BigDecimal minPrice, BigDecimal maxPrice,
      BigDecimal tickSize, int priceScale, int currencyScale,
      long minQuantity, long maxQuantity, long discoveryQuantity) {
    public StructuralRules {
      requireText(currencyId, "currencyId");
      requirePositive(basePrice, "basePrice");
      requirePositive(minPrice, "minPrice");
      requirePositive(maxPrice, "maxPrice");
      requirePositive(tickSize, "tickSize");
      if (minPrice.compareTo(maxPrice) > 0 || priceScale < 0 || currencyScale < 0
          || minQuantity <= 0 || maxQuantity < minQuantity || discoveryQuantity < minQuantity * 10) {
        throw new IllegalArgumentException("invalid structural market rules");
      }
      if (!fitsScale(tickSize, priceScale) || !fitsScale(minPrice, priceScale)
          || !fitsScale(maxPrice, priceScale)) {
        throw new IllegalArgumentException("tick and price bounds must fit priceScale");
      }
    }
  }

  public record RiskRules(
      BigDecimal makerFeeRate, BigDecimal takerFeeRate,
      BigDecimal priceCageRatio, BigDecimal defaultMarketSlippage,
      BigDecimal maximumMarketSlippage, BigDecimal levelOneMove,
      long levelOneHaltSeconds, BigDecimal levelTwoMove, long levelTwoHaltSeconds,
      long maxAccountHolding, BigDecimal maxFrozenCurrency, int maxOpenOrders,
      int operationsPerSecond, int operationsPerMinute) {
    public RiskRules {
      requireNonNegative(makerFeeRate, "makerFeeRate");
      requireNonNegative(takerFeeRate, "takerFeeRate");
      requireNonNegative(priceCageRatio, "priceCageRatio");
      requireNonNegative(defaultMarketSlippage, "defaultMarketSlippage");
      requireNonNegative(maximumMarketSlippage, "maximumMarketSlippage");
      requireNonNegative(levelOneMove, "levelOneMove");
      requireNonNegative(levelTwoMove, "levelTwoMove");
      requirePositive(maxFrozenCurrency, "maxFrozenCurrency");
      if (defaultMarketSlippage.compareTo(maximumMarketSlippage) > 0
          || maximumMarketSlippage.compareTo(new BigDecimal("0.20")) > 0
          || levelOneHaltSeconds <= 0 || levelTwoHaltSeconds <= 0 || maxAccountHolding <= 0
          || maxOpenOrders <= 0 || operationsPerSecond <= 0 || operationsPerMinute <= 0) {
        throw new IllegalArgumentException("invalid market risk rules");
      }
    }
  }

  private static void requireText(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requirePositive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(BigDecimal value, String name) {
    if (value == null || value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static boolean fitsScale(BigDecimal value, int scale) {
    return value.stripTrailingZeros().scale() <= scale;
  }
}
