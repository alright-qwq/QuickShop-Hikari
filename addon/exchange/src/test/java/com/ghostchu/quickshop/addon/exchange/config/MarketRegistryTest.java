package com.ghostchu.quickshop.addon.exchange.config;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.platform.FingerprintMode;
import java.math.BigDecimal;
import java.io.File;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.bukkit.Material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketRegistryTest {
  @Test
  void loadsConfirmedRiskDefaults() {
    MarketRegistry registry = new MarketRegistry(Map.of("minecraft_diamond/default", definition("0.01")));

    MarketDefinition diamond = registry.require("minecraft_diamond/default");

    assertThat(diamond.risk().priceCageRatio()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().defaultMarketSlippage()).isEqualByComparingTo("0.05");
    assertThat(diamond.risk().maximumMarketSlippage()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().operationsPerSecond()).isEqualTo(5);
    assertThat(diamond.risk().operationsPerMinute()).isEqualTo(60);
  }

  @Test
  void loadsConfirmedRiskDefaultsFromBundledYaml() throws Exception {
    File config = new File(getClass().getClassLoader().getResource("config.yml").toURI());
    File markets = new File(getClass().getClassLoader().getResource("markets.yml").toURI());

    MarketDefinition diamond = MarketRegistry.load(config, markets)
        .require("minecraft_diamond/default");

    assertThat(diamond.risk().priceCageRatio()).isEqualByComparingTo("0.20");
    assertThat(diamond.risk().defaultMarketSlippage()).isEqualByComparingTo("0.05");
    assertThat(diamond.risk().maximumMarketSlippage()).isEqualByComparingTo("0.20");
  }

  @Test
  void structuralReloadRequiresPausedEmptyBook() {
    MarketRegistry registry = new MarketRegistry(Map.of("minecraft_diamond/default", definition("0.01")));
    MarketStateReader state = market -> new MarketStateReader.State(MarketStatus.OPEN, 3);

    assertThatThrownBy(() -> registry.reload(
        Map.of("minecraft_diamond/default", definition("0.02")), state))
        .hasMessageContaining("structural change requires PAUSED market with no open orders");
  }

  @Test
  void feeReloadAppendsAnImmutableVersion() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "minecraft_diamond/default", definition("0.01", "0.001", "0.002")));

    registry.reload(Map.of("minecraft_diamond/default",
        definition("0.01", "0.010", "0.020")),
        market -> new MarketStateReader.State(MarketStatus.OPEN, 3));

    MarketRegistry.FeeSchedule schedule = registry.feeSchedule("minecraft_diamond/default");
    assertThat(schedule.activeVersion()).isEqualTo(2);
    assertThat(schedule.versions()).containsOnlyKeys(1L, 2L);
    assertThat(schedule.versions().get(1L).makerRate()).isEqualByComparingTo("0.001");
    assertThat(schedule.versions().get(2L).makerRate()).isEqualByComparingTo("0.010");
  }

  @Test
  void rejectsTickSizeBeyondConfiguredPriceScale() {
    assertThatThrownBy(() -> definition("0.001"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceScale");
  }

  @Test
  void doesNotPublishAnyCandidateWhenAtomicPersistenceFails() {
    MarketRegistry registry = new MarketRegistry(Map.of(
        "diamond", definition("diamond", "0.01", "0.001", "0.002", 2),
        "emerald", definition("emerald", "0.01", "0.001", "0.002", 2)),
        states -> { throw new IllegalStateException("database unavailable"); });

    assertThatThrownBy(() -> registry.reload(Map.of(
        "diamond", definition("diamond", "0.02", "0.001", "0.002", 2),
        "emerald", definition("emerald", "0.02", "0.001", "0.002", 2)),
        market -> new MarketStateReader.State(MarketStatus.PAUSED, 0)))
        .hasMessageContaining("database unavailable");

    assertThat(registry.require("diamond").structural().tickSize())
        .isEqualByComparingTo("0.01");
    assertThat(registry.require("emerald").structural().tickSize())
        .isEqualByComparingTo("0.01");
    assertThat(registry.versions("diamond")).isEqualTo(new MarketRegistry.Versions(1, 1, 1));
    assertThat(registry.versions("emerald")).isEqualTo(new MarketRegistry.Versions(1, 1, 1));
  }

  @Test
  void blocksOnlyNewContainerShopsForConfiguredVanillaMaterials() {
    MarketDefinition protectedDiamond = new MarketDefinition("diamond", "Diamond", false,
        new MarketDefinition.ItemDefinition(FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        definition("diamond", "0.01", "0.001", "0.002", 2).structural(),
        definition("diamond", "0.01", "0.001", "0.002", 2).risk(), true);
    MarketRegistry registry = new MarketRegistry(Map.of("diamond", protectedDiamond));

    assertThat(registry.blocksContainerShop(Material.DIAMOND)).isTrue();
    assertThat(registry.blocksContainerShop(Material.EMERALD)).isFalse();
  }

  private static MarketDefinition definition(String tickSize) {
    return definition(tickSize, "0.001", "0.002");
  }

  private static MarketDefinition definition(
      String tickSize, String makerFeeRate, String takerFeeRate) {
    return definition("minecraft_diamond/default", tickSize, makerFeeRate, takerFeeRate, 2);
  }

  private static MarketDefinition definition(
      String marketId, String tickSize, String makerFeeRate, String takerFeeRate,
      int currencyScale) {
    return new MarketDefinition(marketId, "Diamond", false,
        new MarketDefinition.ItemDefinition(FingerprintMode.VANILLA_MATERIAL, "DIAMOND", null, null),
        new MarketDefinition.StructuralRules("default", new BigDecimal("100.00"),
            BigDecimal.ONE, new BigDecimal("10000.00"), new BigDecimal(tickSize), 2, currencyScale,
            1, 2304, 100),
        new MarketDefinition.RiskRules(new BigDecimal(makerFeeRate), new BigDecimal(takerFeeRate),
            new BigDecimal("0.20"), new BigDecimal("0.05"), new BigDecimal("0.20"),
            new BigDecimal("0.10"), 120, new BigDecimal("0.20"), 600, 100000,
            new BigDecimal("10000000.00"), 100, 5, 60), false);
  }
}
