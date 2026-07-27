package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
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
}
