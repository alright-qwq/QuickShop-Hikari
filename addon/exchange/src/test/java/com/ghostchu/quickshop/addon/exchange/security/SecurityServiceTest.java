package com.ghostchu.quickshop.addon.exchange.security;

import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.SqliteTestDatabase;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityAuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityBalance;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityDefinitionState;
import com.ghostchu.quickshop.addon.exchange.repository.SecurityLedgerEntry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityServiceTest {
  @TempDir
  Path temp;

  private ConnectionProvider connections;
  private TableNames tables;
  private ExchangeRepository repository;
  private SecurityService service;
  private final String marketId = "concept_alpha";

  @BeforeEach
  void createRepository() throws Exception {
    connections = SqliteTestDatabase.at(temp.resolve("security-service.db"));
    tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    service = new SecurityService(repository);
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.markets()
          + " (market_id,currency_id,item_fingerprint,item_template,structural_payload,"
          + "fee_schedule_payload,risk_payload,structural_version,risk_version,created_at)"
          + " VALUES ('" + marketId + "','default','','','{}','{}','{}',1,1,0)");
    }
  }

  @Test
  void createPersistsOpenDefinitionAndReplaysDuplicateRequest() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID request = UUID.randomUUID();

    SecurityMutationResult created = service.create(actor, request, marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 1);

    assertThat(created.replayed()).isFalse();
    assertThat(created.status()).isEqualTo("OPEN");
    SecurityDefinitionState definition =
        repository.inTransaction(tx -> tx.securityDefinition(marketId));
    assertThat(definition.symbol()).isEqualTo("ALPHA");
    assertThat(definition.issuedSupply()).isZero();
    Optional<SecurityAuditRecord> storedAudit =
        repository.inTransaction(tx -> tx.securityAudit(request.toString()));
    assertThat(storedAudit).isPresent();

    SecurityMutationResult replayed = service.create(actor, request, marketId, "ALPHA", "Alpha",
        "Concept stock", "default", new BigDecimal("10.00"), 1000, 1);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.status()).isEqualTo("OPEN");
  }

  @Test
  void issueCreditsTargetAndIsIdempotentByRequest() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    createOpen(actor, 1000, 1);

    service.issue(actor, request, marketId, owner, 100, "initial grant");

    SecurityBalance balance =
        repository.inTransaction(tx -> tx.securityBalance(owner, marketId));
    assertThat(balance.availableQuantity()).isEqualTo(100);
    long issued = repository.inTransaction(tx -> tx.securityDefinition(marketId).issuedSupply());
    assertThat(issued).isEqualTo(100);
    List<SecurityLedgerEntry> ledger =
        repository.inTransaction(tx -> tx.securityLedger(marketId, owner));
    assertThat(ledger).hasSize(1);

    SecurityMutationResult replayed =
        service.issue(actor, request, marketId, owner, 100, "initial grant");
    assertThat(replayed.replayed()).isTrue();
    SecurityBalance after =
        repository.inTransaction(tx -> tx.securityBalance(owner, marketId));
    assertThat(after.availableQuantity()).isEqualTo(100);
  }

  @Test
  void issueRejectsOverIssuanceAndBadUnit() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    createOpen(actor, 100, 10);

    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, owner, 15, "invalid unit"))
        .hasMessageContaining("multiple of minimum unit");
    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, owner, 200, "over issuance"))
        .hasMessageContaining("insufficient unissued supply");
    long issued = repository.inTransaction(tx -> tx.securityDefinition(marketId).issuedSupply());
    assertThat(issued).isZero();
  }

  @Test
  void pauseAndResumeEnforceStateTransitions() throws Exception {
    UUID actor = UUID.randomUUID();
    createOpen(actor, 100, 1);

    service.pause(actor, UUID.randomUUID(), marketId, "temporary halt");
    String paused = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(paused).isEqualTo("PAUSED");
    assertThatThrownBy(() -> service.pause(
        actor, UUID.randomUUID(), marketId, "second pause"))
        .isInstanceOf(IllegalStateException.class);

    service.resume(actor, UUID.randomUUID(), marketId, "resume trading");
    String open = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(open).isEqualTo("OPEN");
    assertThatThrownBy(() -> service.resume(
        actor, UUID.randomUUID(), marketId, "resume again"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void closeRejectsMarketWithOpenOrders() throws Exception {
    UUID actor = UUID.randomUUID();
    createOpen(actor, 100, 1);
    insertOpenOrder(actor);

    assertThatThrownBy(() -> service.close(
        actor, UUID.randomUUID(), marketId, UUID.randomUUID(), "close the stock"))
        .hasMessageContaining("no open orders");
    String status = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(status).isEqualTo("OPEN");
  }

  @Test
  void closeRecoversAllOutstandingBalancesAndBlocksFurtherIssuance() throws Exception {
    UUID actor = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID recovery = UUID.randomUUID();
    createOpen(actor, 1000, 1);
    service.issue(actor, UUID.randomUUID(), marketId, first, 100, "first allocation");
    service.issue(actor, UUID.randomUUID(), marketId, second, 50, "second allocation");
    repository.inTransaction(tx -> {
      tx.freezeSecurity(first, marketId, 30);
      return null;
    });

    SecurityMutationResult closed = service.close(
        actor, UUID.randomUUID(), marketId, recovery, "close the stock");

    assertThat(closed.status()).isEqualTo("CLOSED");
    SecurityBalance firstBalance =
        repository.inTransaction(tx -> tx.securityBalance(first, marketId));
    SecurityBalance secondBalance =
        repository.inTransaction(tx -> tx.securityBalance(second, marketId));
    SecurityBalance recoveryBalance =
        repository.inTransaction(tx -> tx.securityBalance(recovery, marketId));
    assertThat(firstBalance.availableQuantity()).isZero();
    assertThat(firstBalance.frozenQuantity()).isZero();
    assertThat(secondBalance.availableQuantity()).isZero();
    assertThat(secondBalance.frozenQuantity()).isZero();
    assertThat(recoveryBalance.availableQuantity()).isEqualTo(150);
    String status = repository.inTransaction(tx -> tx.securityDefinition(marketId).status());
    assertThat(status).isEqualTo("CLOSED");

    assertThatThrownBy(() -> service.issue(
        actor, UUID.randomUUID(), marketId, first, 1, "issue after close"))
        .isInstanceOf(IllegalStateException.class);
  }

  private void createOpen(UUID actor, long totalSupply, long minimumUnit) throws Exception {
    service.create(actor, UUID.randomUUID(), marketId, "ALPHA", "Alpha", "Concept stock",
        "default", new BigDecimal("10.00"), totalSupply, minimumUnit);
  }

  private void insertOpenOrder(UUID owner) throws Exception {
    try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO " + tables.orders()
          + " (order_id,request_id,market_id,account_id,side,order_type,time_in_force,"
          + "limit_price,original_quantity,remaining_quantity,status,priority_sequence,config_version,"
          + "fee_version,reserved_currency,reserved_quantity,created_at,updated_at,version)"
          + " VALUES ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "','" + marketId
          + "','" + owner
          + "','SELL','LIMIT','GTC','10.00',10,10,'OPEN',1,1,1,'0.00',10,1,1,0)");
    }
  }
}
