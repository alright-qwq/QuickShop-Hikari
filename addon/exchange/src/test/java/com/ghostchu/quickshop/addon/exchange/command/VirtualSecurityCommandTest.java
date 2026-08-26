package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.operations.AdminExchangeService;
import com.ghostchu.quickshop.addon.exchange.security.SecurityService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirtualSecurityCommandTest {
  @Test
  void createsAndIssuesStockThroughAdminRouter() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");

    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});
    assertThat(actor.message).isEqualTo("request-accepted");

    UUID owner = UUID.randomUUID();
    fixture.router.execute(actor, new String[] {"stock", "issue", "alpha", owner.toString(),
        "100", "initial allocation"});
    assertThat(actor.message).isEqualTo("request-accepted");

    Long issued = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").issuedSupply());
    assertThat(issued).isEqualTo(100);
  }

  @Test
  void deniesStockCommandsWithoutDedicatedPermission() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.market");

    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});

    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void pausesResumesAndClosesStock() throws Exception {
    Fixture fixture = new Fixture();
    Actor actor = new Actor("quickshop.exchange.admin.stock");
    fixture.router.execute(actor, new String[] {"stock", "create", "ALPHA", "Alpha",
        "default", "10.00", "1000", "1", "concept stock"});

    fixture.router.execute(actor, new String[] {"stock", "pause", "alpha", "temporary halt"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String paused = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(paused).isEqualTo("PAUSED");

    fixture.router.execute(actor, new String[] {"stock", "resume", "alpha", "resume trading"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String open = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(open).isEqualTo("OPEN");

    UUID recovery = UUID.randomUUID();
    fixture.router.execute(actor, new String[] {"stock", "close", "alpha", recovery.toString(),
        "close the stock"});
    assertThat(actor.message).isEqualTo("request-accepted");
    String closed = fixture.repository.inTransaction(
        tx -> tx.securityDefinition("alpha").status());
    assertThat(closed).isEqualTo("CLOSED");
  }

  @Test
  void routesPlayerStocksAndStockDetailPages() {
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);
    Actor actor = new Actor("quickshop.exchange.use");

    router.execute(actor, new String[] {"stocks"});
    assertThat(actor.page).isEqualTo("markets");

    router.execute(actor, new String[] {"stock", "alpha"});
    assertThat(actor.page).isEqualTo("market-detail");
  }

  private static final class Fixture {
    private final com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider connections;
    private final com.ghostchu.quickshop.addon.exchange.persistence.TableNames tables;
    private final com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository repository;
    private final AdminCommandRouter router;

    private Fixture() throws Exception {
      java.nio.file.Path path = java.nio.file.Files.createTempFile("qs-command-stock-", ".db");
      path.toFile().deleteOnExit();
      connections = com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase.at(path);
      tables = new com.ghostchu.quickshop.addon.exchange.persistence.TableNames("qs_");
      new com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner(
          connections, com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect.SQLITE,
          tables).migrate();
      repository = new com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository(
          connections, com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect.SQLITE, tables);
      try (java.sql.Connection connection = connections.open();
           java.sql.Statement statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO " + tables.markets()
            + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
            + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
            + " VALUES ('alpha','default','','','{}','{}','{}',1,1,0)");
      }
      router = new AdminCommandRouter(
          new AdminExchangeService(Map.of(), repository, null, null, new SecurityService(repository)),
          UUID::randomUUID);
    }
  }

  private static final class Actor implements CommandActor {
    private final UUID accountId = UUID.randomUUID();
    private final Set<String> permissions = new HashSet<>();
    private String message;
    private String page;

    private Actor(String... permissions) {
      this.permissions.addAll(Set.of(permissions));
    }

    @Override public UUID accountId() { return accountId; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void message(String key, Object... arguments) { message = key; }
    @Override public void openMenu(String menuName, int page) { this.page = menuName; }
  }
}
