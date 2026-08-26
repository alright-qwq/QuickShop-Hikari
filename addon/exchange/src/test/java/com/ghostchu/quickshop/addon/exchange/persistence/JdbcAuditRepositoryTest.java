package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.operations.AuditAlert;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.OrderActivity;
import com.ghostchu.quickshop.addon.exchange.operations.SuspiciousTradingDetector.TradeActivity;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcAuditRepositoryTest {
  @TempDir Path temp;

  @Test
  void persistsAppendOnlyAuditRecordsAndReadsBoundedRange() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("audit.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    Instant start = Instant.parse("2026-07-28T00:00:00Z");
    AuditRecord included = new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "FORCE_CANCEL",
        "order-1", "suspected abuse", "OPEN", "CANCELLED", start.plusSeconds(1));
    AuditRecord excluded = new AuditRecord(UUID.randomUUID(), UUID.randomUUID(), "FORCE_CANCEL",
        "order-2", "suspected abuse", "OPEN", "CANCELLED", start.minusSeconds(1));

    repository.inTransaction(tx -> {
      tx.appendAudit(included);
      tx.appendAudit(excluded);
      return null;
    });

    assertThat(repository.auditRecords(start, start.plusSeconds(10))).containsExactly(included);
  }

  @Test
  void persistsAndReadsAuditAlertsOutsideTransactions() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("alerts.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    Instant at = Instant.parse("2026-08-27T12:00:00Z");
    AuditAlert accountAlert = new AuditAlert(UUID.randomUUID(), "concept-stock", UUID.randomUUID(),
        "HIGH_FREQUENCY_RECIPROCAL_TRADING", "MEDIUM", "trades=3", at, null);
    AuditAlert marketAlert = new AuditAlert(UUID.randomUUID(), "concept-stock", null,
        "HIGH_CANCEL_PLACE_RATIO", "MEDIUM", "ratio=1.0", at.plusSeconds(10), at.plusSeconds(60));

    repository.insertAuditAlert(accountAlert);
    repository.insertAuditAlert(marketAlert);

    assertThat(repository.recentAlerts(20)).containsExactly(marketAlert, accountAlert);
    assertThat(repository.openAlerts(20)).containsExactly(accountAlert);
    assertThat(repository.recentAlerts(1)).containsExactly(marketAlert);
  }

  @Test
  void acknowledgesOnlyOpenAlerts() throws Exception {
    ConnectionProvider connections = SqliteTestDatabase.at(temp.resolve("alerts-ack.db"));
    TableNames tables = new TableNames("qs_");
    new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
    ExchangeRepository repository = new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
    Instant at = Instant.ofEpochMilli(Instant.now().toEpochMilli());
    AuditAlert open = new AuditAlert(UUID.randomUUID(), "concept-stock", null,
        "HIGH_CANCEL_PLACE_RATIO", "MEDIUM", "ratio=1.0", at, null);
    AuditAlert acknowledged = new AuditAlert(UUID.randomUUID(), "concept-stock", null,
        "HIGH_FREQUENCY_RECIPROCAL_TRADING", "MEDIUM", "trades=3", at, at);
    repository.insertAuditAlert(open);
    repository.insertAuditAlert(acknowledged);

    repository.acknowledgeAlert(open.alertId(), at.plusSeconds(10));
    repository.acknowledgeAlert(acknowledged.alertId(), at.plusSeconds(20));

    assertThat(repository.openAlerts(20)).isEmpty();
    assertThat(repository.recentAlerts(20)).extracting(AuditAlert::acknowledgedAt)
        .containsExactly(at.plusSeconds(10), at);
  }

  @Test
  void readsPlaceAndCancelActivitiesFromRealSql() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID account = fixture.accountWithCurrency("500.00");
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), account, fixture.rules().marketId(), OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    fixture.service().cancel(account, UUID.randomUUID(), receipt.orderId());
    Instant since = Instant.ofEpochMilli(0);

    var activities = fixture.repository().orderActivities(since);
    var trades = fixture.repository().tradesForDetection(since);

    assertThat(activities).hasSize(2).extracting(OrderActivity::kind)
        .containsExactly(OrderActivity.Kind.PLACE, OrderActivity.Kind.CANCEL);
    assertThat(trades).isEmpty();
  }
}
