package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.persistence.ConnectionProvider;
import com.ghostchu.quickshop.addon.exchange.persistence.JdbcExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.persistence.MigrationRunner;
import com.ghostchu.quickshop.addon.exchange.persistence.SqlDialect;
import com.ghostchu.quickshop.addon.exchange.persistence.TableNames;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferRecoveryServiceTest {
  @ParameterizedTest
  @CsvSource({
      "MONEY_DEPOSIT,REVIEW_REQUIRED",
      "MONEY_WITHDRAWAL,REVIEW_REQUIRED",
      "ITEM_DEPOSIT,REVIEW_REQUIRED",
      "ITEM_WITHDRAWAL,COMPLETED"
  })
  void recoversOnlyWhenExternalMarkerProvesOutcome(
      TransferType type, TransferStatus expected) throws Exception {
    try (Fixture fixture = Fixture.interrupted(type)) {
      if (type == TransferType.ITEM_WITHDRAWAL) {
        fixture.gateway().markedQuantity = 2;
      }

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(expected);
      assertThat(fixture.repository().find(recovered.transferId())).contains(recovered);
    }
  }

  @org.junit.jupiter.api.Test
  void marksInterruptedDepositFailedWhenAllItemsRemainMarked() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_DEPOSIT)) {
      fixture.gateway().markedQuantity = 2;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.FAILED);
      assertThat(fixture.gateway().markedQuantity).isZero();
    }
  }

  @org.junit.jupiter.api.Test
  void keepsInterruptedDepositInReviewWhenMarkerCleanupFails() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_DEPOSIT)) {
      fixture.gateway().markedQuantity = 2;
      fixture.gateway().clearResult = InventoryResult.UNKNOWN;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(fixture.gateway().markedQuantity).isEqualTo(2);
    }
  }

  @org.junit.jupiter.api.Test
  void keepsInterruptedWithdrawalInReviewWhenMarkerCleanupFails() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_WITHDRAWAL)) {
      fixture.gateway().markedQuantity = 2;
      fixture.gateway().clearResult = InventoryResult.OFFLINE;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(fixture.gateway().markedQuantity).isEqualTo(2);
    }
  }

  @org.junit.jupiter.api.Test
  void keepsInterruptedDepositInReviewWhenMarkerCustodyIsExcessive() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_DEPOSIT)) {
      fixture.gateway().markedQuantity = 3;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(fixture.gateway().markedQuantity).isEqualTo(3);
    }
  }

  @org.junit.jupiter.api.Test
  void keepsInterruptedWithdrawalInReviewWhenMarkerCustodyIsExcessive() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_WITHDRAWAL)) {
      fixture.gateway().markedQuantity = 3;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(fixture.gateway().markedQuantity).isEqualTo(3);
    }
  }

  @org.junit.jupiter.api.Test
  void keepsInterruptedWithdrawalInReviewWhenCleanupLeavesMarkedItems() throws Exception {
    try (Fixture fixture = Fixture.interrupted(TransferType.ITEM_WITHDRAWAL)) {
      fixture.gateway().markedQuantity = 2;
      fixture.gateway().clearRemovesMarkers = false;

      TransferRecord recovered = fixture.recovery().recover(fixture.transfer()).join();

      assertThat(recovered.status()).isEqualTo(TransferStatus.REVIEW_REQUIRED);
      assertThat(fixture.gateway().markedQuantity).isEqualTo(2);
    }
  }

  @org.junit.jupiter.api.Test
  void defaultRecoveryFailsClosedForPersistedItemReviewClaims() throws Exception {
    try (Fixture fixture = Fixture.claimedWithdrawal()) {
      TransferRecoveryService recovery = new TransferRecoveryService(
          fixture.repository(), fixture.repository(), fixture.gateway(), Runnable::run);

      assertThatThrownBy(() -> recovery.recover(fixture.transfer()).join())
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("claimed item review recovery is not configured");
    }
  }

  @org.junit.jupiter.api.Test
  void delegatesPersistedItemReviewClaimToMachineRecovery() throws Exception {
    try (Fixture fixture = Fixture.claimedWithdrawal()) {
      java.util.concurrent.atomic.AtomicReference<TransferRecord> delegated =
          new java.util.concurrent.atomic.AtomicReference<>();
      TransferRecoveryService recovery = new TransferRecoveryService(
          fixture.repository(), fixture.repository(), fixture.gateway(), Runnable::run,
          claimed -> {
            delegated.set(claimed);
            return CompletableFuture.completedFuture(claimed);
          });

      TransferRecord recovered = recovery.recover(fixture.transfer()).join();

      assertThat(delegated).hasValue(fixture.transfer());
      assertThat(recovered).isEqualTo(fixture.transfer());
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final JdbcExchangeRepository repository;
    private final TransferRecord transfer;
    private final FakeInventoryGateway gateway = new FakeInventoryGateway();
    private final TransferRecoveryService recovery;

    private Fixture(JdbcExchangeRepository repository, TransferRecord transfer) {
      this.repository = repository;
      this.transfer = transfer;
      this.recovery = new TransferRecoveryService(repository, repository, gateway, Runnable::run);
    }

    static Fixture interrupted(TransferType type) throws Exception {
      Path file = Files.createTempFile("quickshop-exchange-recovery-", ".sqlite");
      file.toFile().deleteOnExit();
      ConnectionProvider connections = () -> DriverManager.getConnection("jdbc:sqlite:" + file);
      TableNames tables = new TableNames("recovery_");
      new MigrationRunner(connections, SqlDialect.SQLITE, tables).migrate();
      JdbcExchangeRepository repository =
          new JdbcExchangeRepository(connections, SqlDialect.SQLITE, tables);
      UUID account = UUID.randomUUID();
      TransferRecord prepared = repository.create(TransferRecord.prepared(
          UUID.randomUUID(), UUID.randomUUID(), account, type, "diamond-usd",
          new BigDecimal("2"), Instant.EPOCH));
      if (type == TransferType.ITEM_WITHDRAWAL) {
        repository.inTransaction(transaction -> {
          transaction.creditAvailableItems(account, "diamond-usd", 2);
          transaction.freezeItems(account, "diamond-usd", 2);
          return null;
        });
      }
      TransferRecord processing = repository.transition(prepared.transferId(), prepared.version(),
          TransferStatus.PREPARED, TransferStatus.PROCESSING, null);
      return new Fixture(repository, processing);
    }

    static Fixture claimedWithdrawal() throws Exception {
      Fixture interrupted = interrupted(TransferType.ITEM_WITHDRAWAL);
      TransferRecord reviewed = interrupted.repository.transition(
          interrupted.transfer.transferId(), interrupted.transfer.version(),
          TransferStatus.PROCESSING, TransferStatus.REVIEW_REQUIRED, "external result unknown");
      TransferRecord claimed = interrupted.repository.inTransaction(transaction ->
          transaction.claimReviewedTransfer(reviewed.transferId(), reviewed.version(),
              "item-review-claim:test"));
      return new Fixture(interrupted.repository, claimed);
    }

    JdbcExchangeRepository repository() {
      return repository;
    }

    TransferRecord transfer() {
      return transfer;
    }

    FakeInventoryGateway gateway() {
      return gateway;
    }

    TransferRecoveryService recovery() {
      return recovery;
    }

    @Override
    public void close() {
    }
  }

  private static final class FakeInventoryGateway implements InventoryGateway {
    private long markedQuantity;
    private InventoryResult clearResult = InventoryResult.SUCCESS;
    private boolean clearRemovesMarkers = true;

    @Override
    public CompletableFuture<InventoryResult> markForDeposit(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<InventoryResult> removeMarked(
        UUID playerId, UUID transferId, long quantity) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<InventoryResult> deliverMarked(
        UUID playerId, ItemStack template, long quantity, UUID transferId) {
      return CompletableFuture.completedFuture(InventoryResult.UNKNOWN);
    }

    @Override
    public CompletableFuture<Long> markedQuantity(UUID playerId, UUID transferId) {
      return CompletableFuture.completedFuture(markedQuantity);
    }

    @Override
    public CompletableFuture<InventoryResult> clearMarker(UUID playerId, UUID transferId) {
      if (clearResult == InventoryResult.SUCCESS && clearRemovesMarkers) {
        markedQuantity = 0;
      }
      return CompletableFuture.completedFuture(clearResult);
    }
  }
}
