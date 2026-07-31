package com.ghostchu.quickshop.addon.exchange.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.ghostchu.quickshop.addon.exchange.config.MarketDefinition;
import com.ghostchu.quickshop.addon.exchange.config.MarketRegistry;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.risk.RiskLimits;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPricePolicy;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.TrustedMarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.service.RecoveryHandler;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedPriceMaintenanceIntegrationTest {
  private static final String MARKET = "minecraft_diamond/default";
  private static final Instant START = Instant.parse("2026-07-31T00:00:00Z");

  @Test
  void bundledDefaultsExposeEveryTrustedPolicyValue() throws Exception {
    Path config = Path.of(getClass().getClassLoader().getResource("config.yml").toURI());
    Path markets = Path.of(getClass().getClassLoader().getResource("markets.yml").toURI());
    TrustedPricePolicy policy = MarketRegistry.load(config.toFile(), markets.toFile())
        .require(MARKET).risk().trustedPricePolicy();

    assertThat(policy.budgetWindow()).isEqualTo(java.time.Duration.ofHours(6));
    assertThat(policy.confidenceWindow()).isEqualTo(java.time.Duration.ofHours(24));
    assertTier(policy, LiquidityTier.LOW, "0.005", "0.030", "0.015", "0.0075", "0.10", "0.005");
    assertTier(policy, LiquidityTier.GROWING, "0.015", "0.080", "0.040", "0.020", "0.25", "0.0015");
    assertTier(policy, LiquidityTier.STABLE, "0.030", "0.200", "0.080", "0.040", "0.60", "0");
  }

  @Test
  void twoHourMaintenancePersistsReversionBeforeRestartWithoutCandleVolume(@TempDir Path temp)
      throws Exception {
    Fixture fixture = fixture(temp);
    MarketDefinition definition = fixture.definition();
    MarketRegistry registry = new MarketRegistry(Map.of(MARKET, definition));
    MarketDataService marketData = new MarketDataService(new CandleAggregator(), fixture.repository());
    PersistentOrderService running = service(fixture.repository(), definition, marketData);
    MutableClock clock = new MutableClock(START.plusSeconds(2 * 60 * 60));

    ExchangeRuntimeFactory.runTrustedPriceMaintenance(
        fixture.repository(), registry, Map.of(MARKET, running), clock.instant());

    TrustedMarketSnapshot persisted = fixture.repository().inTransaction(tx ->
        tx.trustedMarketSnapshot(MARKET, START.minusSeconds(1), START.minusSeconds(1)));
    assertThat(persisted.state().trustedPrice()).isEqualByComparingTo("108.9000000000");
    assertThat(persisted.adjustments()).hasSize(1);
    assertThat(persisted.adjustments().getFirst().reason()).isEqualTo("scheduled anchor reversion");
    assertThat(fixture.repository().loadCandles(MARKET, START, clock.instant().plusSeconds(1)))
        .isEmpty();
    assertThat(running.marketQuote(marketData).referencePrice())
        .isEqualByComparingTo("108.9000000000");

    PersistentOrderService restarted = service(fixture.repository(), definition, marketData);
    restarted.recoverFromDatabase();
    assertThat(restarted.marketQuote(marketData).referencePrice())
        .isEqualByComparingTo("108.9000000000");
  }

  private static void assertTier(TrustedPricePolicy policy, LiquidityTier tier,
                                 String perTrade, String market, String account, String pair,
                                 String anchor, String reversion) {
    TrustedPricePolicy.Tier value = policy.tier(tier);
    assertThat(value.perTradeCap()).isEqualByComparingTo(perTrade);
    assertThat(value.marketBudget()).isEqualByComparingTo(market);
    assertThat(value.accountBudget()).isEqualByComparingTo(account);
    assertThat(value.pairBudget()).isEqualByComparingTo(pair);
    assertThat(value.anchorBand()).isEqualByComparingTo(anchor);
    assertThat(value.reversionPerHour()).isEqualByComparingTo(reversion);
  }

  private static PersistentOrderService service(JdbcExchangeRepository repository,
                                                MarketDefinition definition,
                                                MarketDataService marketData) {
    MarketDefinition.StructuralRules structural = definition.structural();
    MarketDefinition.RiskRules risk = definition.risk();
    MarketRules rules = new MarketRules(definition.marketId(), structural.currencyId(),
        structural.basePrice(), structural.minPrice(), structural.maxPrice(), structural.tickSize(),
        structural.minQuantity(), structural.maxQuantity(), structural.priceScale(),
        risk.makerFeeRate(), risk.takerFeeRate());
    RiskLimits limits = new RiskLimits(risk.priceCageRatio(), risk.defaultMarketSlippage(),
        risk.maximumMarketSlippage(), risk.levelOneMove(),
        java.time.Duration.ofSeconds(risk.levelOneHaltSeconds()), risk.levelTwoMove(),
        java.time.Duration.ofSeconds(risk.levelTwoHaltSeconds()));
    return new PersistentOrderService(repository, rules, limits, RecoveryHandler.NO_OP,
        ExchangeRuntimeFactory.accountLimits(risk), marketData, structural.discoveryQuantity(),
        risk.trustedPricePolicy());
  }

  private static Fixture fixture(Path temp) throws Exception {
    ConnectionProvider connections = new SqliteConnectionProvider(
        () -> DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("maintenance.db")));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    MarketDefinition definition = MarketRegistry.load(
        Path.of(TrustedPriceMaintenanceIntegrationTest.class.getClassLoader()
            .getResource("config.yml").toURI()).toFile(),
        Path.of(TrustedPriceMaintenanceIntegrationTest.class.getClassLoader()
            .getResource("markets.yml").toURI()).toFile()).require(MARKET);
    try (Connection connection = connections.open()) {
      try (PreparedStatement market = connection.prepareStatement(
          "INSERT INTO " + tables.markets()
              + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
              + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
        market.setString(1, MARKET);
        market.setString(2, definition.structural().currencyId());
        market.setString(3, definition.item().material());
        market.setString(4, "{}");
        market.setString(5, "{}");
        market.setString(6, "{}");
        market.setString(7, "{}");
        market.setLong(8, 1);
        market.setLong(9, 1);
        market.setLong(10, START.toEpochMilli());
        market.executeUpdate();
      }
      try (PreparedStatement state = connection.prepareStatement(
          "INSERT INTO " + tables.marketState()
              + " (market_id,status,priority_sequence,match_sequence,reference_price,version)"
              + " VALUES (?,?,?,?,?,?)")) {
        state.setString(1, MARKET);
        state.setString(2, "OPEN");
        state.setLong(3, 0);
        state.setLong(4, 0);
        state.setString(5, "100.00");
        state.setLong(6, 0);
        state.executeUpdate();
      }
      try (PreparedStatement state = connection.prepareStatement(
          "INSERT INTO " + tables.trustedMarketState()
              + " (market_id,trusted_price,guidance_price,last_evaluated_at,confidence_tier,"
              + "policy_version,last_match_sequence,state_version) VALUES (?,?,?,?,?,?,?,?)")) {
        state.setString(1, MARKET);
        state.setString(2, "110.0000000000");
        state.setString(3, "100.00");
        state.setLong(4, START.toEpochMilli());
        state.setString(5, "LOW");
        state.setLong(6, 1);
        state.setLong(7, 0);
        state.setLong(8, 0);
        state.executeUpdate();
      }
    }
    return new Fixture(new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables), definition);
  }

  private record Fixture(JdbcExchangeRepository repository, MarketDefinition definition) {}

  private static final class MutableClock extends Clock {
    private final Instant instant;
    private MutableClock(Instant instant) { this.instant = instant; }
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return instant; }
  }
}
