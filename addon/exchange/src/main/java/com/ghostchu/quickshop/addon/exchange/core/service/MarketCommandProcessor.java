package com.ghostchu.quickshop.addon.exchange.core.service;

@FunctionalInterface
public interface MarketCommandProcessor {
  CommandResult process(ExchangeCommand command);
}
