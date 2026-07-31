package com.ghostchu.quickshop.addon.exchange.core.trust;

/** Hard limit that reduced a raw trade's accepted trusted-price movement. */
public enum LimitReason {
  LIMITED_BY_TRADE,
  LIMITED_BY_MARKET,
  LIMITED_BY_ACCOUNT,
  LIMITED_BY_PAIR,
  LIMITED_BY_ANCHOR
}
