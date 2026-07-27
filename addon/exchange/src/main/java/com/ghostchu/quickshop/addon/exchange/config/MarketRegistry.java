package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.io.File;
import java.math.BigDecimal;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import java.util.Collections;

/** Holds market configuration and only permits structural changes on a paused empty book. */
public final class MarketRegistry {
  private final Map<String, Entry> markets = new LinkedHashMap<>();

  public MarketRegistry(Map<String, MarketDefinition> definitions) {
    replaceInitial(definitions);
  }

  public static MarketRegistry load(File configurationFile, File marketsFile) {
    YamlConfiguration configuration = YamlConfiguration.loadConfiguration(configurationFile);
    YamlConfiguration markets = YamlConfiguration.loadConfiguration(marketsFile);
    ConfigurationSection riskDefaults = requiredSection(configuration, "risk-defaults");
    ConfigurationSection configuredMarkets = requiredSection(markets, "markets");
    Map<String, MarketDefinition> definitions = new LinkedHashMap<>();
    for (String marketId : configuredMarkets.getKeys(false)) {
      ConfigurationSection market = requiredSection(configuredMarkets, marketId);
      ConfigurationSection item = requiredSection(market, "item");
      definitions.put(marketId, new MarketDefinition(marketId,
          requiredString(market, "display-name"), market.getBoolean("enabled"),
          new MarketDefinition.ItemDefinition(
              FingerprintMode.valueOf(requiredString(item, "mode")),
              requiredString(item, "material"), item.getString("encoded-template"),
              item.getString("fingerprint")),
          new MarketDefinition.StructuralRules(requiredString(market, "currency"),
              decimal(market, "base-price"), decimal(market, "min-price"),
              decimal(market, "max-price"), decimal(market, "tick-size"),
              market.getInt("price-scale"), market.getInt("currency-scale"),
              market.getLong("min-quantity"), market.getLong("max-quantity"),
              market.getLong("discovery-quantity")),
          new MarketDefinition.RiskRules(decimal(market, "maker-fee-rate"),
              decimal(market, "taker-fee-rate"), decimal(riskDefaults, "price-cage-ratio"),
              decimal(riskDefaults, "default-market-slippage"),
              decimal(riskDefaults, "maximum-market-slippage"),
              decimal(riskDefaults, "level-one-move"), riskDefaults.getLong("level-one-halt-seconds"),
              decimal(riskDefaults, "level-two-move"), riskDefaults.getLong("level-two-halt-seconds"),
              market.getLong("max-account-holding"), decimal(market, "max-frozen-currency"),
              market.getInt("max-open-orders"), riskDefaults.getInt("operations-per-second"),
              riskDefaults.getInt("operations-per-minute")), market.getBoolean("block-container-shops")));
    }
    return new MarketRegistry(definitions);
  }

  public synchronized MarketDefinition require(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return entry.definition;
  }

  public synchronized Versions versions(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return new Versions(entry.structuralVersion, entry.riskVersion, entry.feeVersion);
  }

  public synchronized FeeSchedule feeSchedule(String marketId) {
    Entry entry = markets.get(marketId);
    if (entry == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return new FeeSchedule(entry.feeVersion,
        Collections.unmodifiableMap(new LinkedHashMap<>(entry.feeSchedule)));
  }

  public synchronized void reload(
      Map<String, MarketDefinition> replacements, MarketStateReader stateReader) {
    Objects.requireNonNull(replacements, "replacements");
    Objects.requireNonNull(stateReader, "stateReader");
    if (!markets.keySet().equals(replacements.keySet())) {
      throw new IllegalArgumentException("market set cannot change during reload");
    }
    for (Map.Entry<String, MarketDefinition> replacement : replacements.entrySet()) {
      Entry current = markets.get(replacement.getKey());
      MarketDefinition next = replacement.getValue();
      if (!current.definition.item().equals(next.item())
          || !current.definition.structural().equals(next.structural())) {
        MarketStateReader.State state = stateReader.read(replacement.getKey());
        if (state.status() != MarketStatus.PAUSED || state.openOrders() != 0) {
          throw new IllegalStateException(
              "structural change requires PAUSED market with no open orders");
        }
        current.structuralVersion++;
      }
      if (!current.definition.risk().equals(next.risk())) {
        current.riskVersion++;
        if (current.definition.risk().makerFeeRate().compareTo(next.risk().makerFeeRate()) != 0
            || current.definition.risk().takerFeeRate().compareTo(next.risk().takerFeeRate()) != 0) {
          current.feeVersion++;
          current.feeSchedule.put(current.feeVersion, feeRates(next));
        }
      }
      current.definition = next;
    }
  }

  private void replaceInitial(Map<String, MarketDefinition> definitions) {
    if (definitions == null || definitions.isEmpty()) {
      throw new IllegalArgumentException("at least one market is required");
    }
    definitions.forEach((marketId, definition) -> {
      if (!marketId.equals(definition.marketId())) {
        throw new IllegalArgumentException("market key does not match definition");
      }
      markets.put(marketId, new Entry(definition));
    });
  }

  private static ConfigurationSection requiredSection(ConfigurationSection parent, String path) {
    ConfigurationSection section = parent.getConfigurationSection(path);
    if (section == null) {
      throw new IllegalArgumentException("missing configuration section: " + path);
    }
    return section;
  }

  private static String requiredString(ConfigurationSection section, String path) {
    String value = section.getString(path);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing configuration value: " + path);
    }
    return value;
  }

  private static BigDecimal decimal(ConfigurationSection section, String path) {
    return new BigDecimal(requiredString(section, path));
  }

  public record Versions(long structuralVersion, long riskVersion, long feeVersion) {
  }

  public record FeeSchedule(long activeVersion, Map<Long, FeeRates> versions) {
  }

  private static FeeRates feeRates(MarketDefinition definition) {
    return new FeeRates(definition.risk().makerFeeRate(), definition.risk().takerFeeRate());
  }

  private static final class Entry {
    private MarketDefinition definition;
    private long structuralVersion = 1;
    private long riskVersion = 1;
    private long feeVersion = 1;
    private final Map<Long, FeeRates> feeSchedule = new LinkedHashMap<>();

    private Entry(MarketDefinition definition) {
      this.definition = definition;
      this.feeSchedule.put(feeVersion, feeRates(definition));
    }
  }
}
