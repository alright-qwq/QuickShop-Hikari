package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.config.AssetType;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.security.SecurityStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Small locale-aware adapter for player-visible exchange menu text. */
final class ExchangeUiMessages {
  private final AddonMessageService messages;

  ExchangeUiMessages(AddonMessageService messages) {
    this.messages = messages;
  }

  Component component(Player player, String key, Object... arguments) {
    return Component.text(text(player, key, arguments));
  }

  String text(Player player, String key, Object... arguments) {
    if (messages == null) return key;
    Locale locale = player.locale();
    return messages.message(key, locale, arguments);
  }

  /** Formats an aggregate or market-agnostic currency amount at the default two-decimal scale. */
  String formatCurrency(BigDecimal value) {
    return formatCurrency(value, 2);
  }

  /** Formats a currency amount with an explicit scale, falling back to two decimals. */
  String formatCurrency(BigDecimal value, int priceScale) {
    if (value == null) return "-";
    int scale = priceScale < 0 ? 2 : priceScale;
    return value.setScale(scale, RoundingMode.HALF_UP).toPlainString();
  }

  /** Renders user-facing enums in the viewer locale instead of leaking Java names. */
  Object localized(Player player, Object value) {
    if (value == null) return "-";
    if (value instanceof OrderSide orderSide) {
      return text(player, orderSide == OrderSide.BUY ? "ui-history-trade-buy"
          : "ui-history-trade-sell");
    }
    if (value instanceof OrderType orderType) {
      return text(player, orderType == OrderType.LIMIT
          ? "ui-order-type-limit" : "ui-order-type-market");
    }
    if (value instanceof OrderStatus orderStatus) {
      return text(player, switch (orderStatus) {
        case OPEN -> "ui-order-status-open";
        case PARTIALLY_FILLED -> "ui-order-status-partially-filled";
        case FILLED -> "ui-order-status-filled";
        case CANCELLED -> "ui-order-status-cancelled";
        case REJECTED -> "ui-order-status-rejected";
      });
    }
    if (value instanceof MarketStatus marketStatus) {
      return text(player, switch (marketStatus) {
        case OPEN -> "ui-market-state-open";
        case HALTED -> "ui-market-state-halted";
        case PAUSED -> "ui-market-state-paused";
        case RECOVERING -> "ui-market-state-recovering";
        case CLOSED -> "ui-market-state-closed";
      });
    }
    if (value instanceof AssetType assetType) {
      return text(player, assetType == AssetType.PHYSICAL_ITEM
          ? "ui-asset-type-physical-item" : "ui-asset-type-virtual-security");
    }
    if (value instanceof SecurityStatus securityStatus) {
      return text(player, switch (securityStatus) {
        case OPEN -> "ui-security-state-open";
        case PAUSED -> "ui-security-state-paused";
        case HALTED -> "ui-security-state-halted";
        case CLOSED -> "ui-security-state-closed";
      });
    }
    if (value instanceof TransferType transferType) {
      return text(player, switch (transferType) {
        case MONEY_DEPOSIT -> "ui-transfer-type-money-deposit";
        case MONEY_WITHDRAWAL -> "ui-transfer-type-money-withdrawal";
        case ITEM_DEPOSIT -> "ui-transfer-type-item-deposit";
        case ITEM_WITHDRAWAL -> "ui-transfer-type-item-withdrawal";
      });
    }
    if (value instanceof TransferStatus transferStatus) {
      return text(player, switch (transferStatus) {
        case PREPARED -> "ui-transfer-status-prepared";
        case PROCESSING -> "ui-transfer-status-processing";
        case COMPLETED -> "ui-transfer-status-completed";
        case FAILED -> "ui-transfer-status-failed";
        case REVIEW_REQUIRED -> "ui-transfer-status-review-required";
      });
    }
    // Values loaded from configuration (asset/security status) use canonical uppercase names.
    if (value instanceof String raw) {
      String key = switch (raw.toUpperCase(java.util.Locale.ROOT)) {
        case "PHYSICAL_ITEM" -> "ui-asset-type-physical-item";
        case "VIRTUAL_SECURITY" -> "ui-asset-type-virtual-security";
        case "OPEN" -> "ui-market-state-open";
        case "PAUSED" -> "ui-market-state-paused";
        case "HALTED" -> "ui-market-state-halted";
        case "CLOSED" -> "ui-market-state-closed";
        case "TRADE_CURRENCY" -> "ui-ledger-type-trade-currency";
        case "TRADE_ITEM" -> "ui-ledger-type-trade-item";
        case "MONEY_DEPOSIT" -> "ui-transfer-type-money-deposit";
        case "MONEY_WITHDRAWAL" -> "ui-transfer-type-money-withdrawal";
        case "ITEM_DEPOSIT" -> "ui-transfer-type-item-deposit";
        case "ITEM_WITHDRAWAL" -> "ui-transfer-type-item-withdrawal";
        default -> null;
      };
      return key == null ? raw : text(player, key);
    }
    return String.valueOf(value);
  }

  /** Localizes a rejection/error reason for the player, falling back to the raw reason. */
  String reasonText(Player player, String rawReason) {
    return localizeReason(messages, player.locale(), rawReason);
  }

  /** Pure reason localization used by the player-facing adapter and tests. */
  static String localizeReason(AddonMessageService messages, Locale locale, String rawReason) {
    if (rawReason == null || rawReason.isBlank()) {
      return "";
    }
    String key = switch (rawReason.toUpperCase(Locale.ROOT)) {
      case "MARKET_NOT_OPEN" -> "ui-reject-market-not-open";
      case "RATE_LIMITED" -> "ui-reject-rate-limited";
      case "PRICE_OUTSIDE_CAGE" -> "ui-reject-price-outside-cage";
      case "SLIPPAGE_TOO_HIGH" -> "ui-reject-slippage-too-high";
      case "HOLDING_LIMIT" -> "ui-reject-holding-limit";
      case "FROZEN_LIMIT" -> "ui-reject-frozen-limit";
      case "OPEN_ORDER_LIMIT" -> "ui-reject-open-order-limit";
      case "SELF_TRADE" -> "ui-reject-self-trade";
      case "INVENTORY_FULL" -> "inventory-full";
      default -> null;
    };
    if (key != null) {
      String localized = messages.message(key, locale);
      if (!localized.equals(key)) {
        return localized;
      }
    }
    return messages.message("ui-reject-fallback", locale, rawReason);
  }

  /** Compact relative time like "3m ago", "2h ago", or "2026-08-26" for very old timestamps. */
  String relativeTime(Instant at) {
    if (at == null) {
      return "-";
    }
    long seconds = Duration.between(at, Instant.now()).getSeconds();
    if (seconds < 0) {
      return at.toString();
    }
    if (seconds < 60) {
      return seconds + "s";
    }
    if (seconds < 3600) {
      return (seconds / 60) + "m";
    }
    if (seconds < 86400) {
      return (seconds / 3600) + "h";
    }
    if (seconds < 86400L * 30) {
      return (seconds / 86400) + "d";
    }
    return at.toString();
  }
}
