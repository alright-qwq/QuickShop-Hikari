package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryPageSnapshotTest {
  @Test
  void combinesBoundedTradeTransferAndLiabilityLedgerPages() {
    HistoryPageSnapshot snapshot = HistoryPageSnapshot.combine(
        CompletableFuture.<List<Trade>>completedFuture(List.of()),
        CompletableFuture.<List<TransferRecord>>completedFuture(List.of()),
        CompletableFuture.<List<AccountLedgerEntry>>completedFuture(List.of())).join();

    assertThat(snapshot.trades()).isEmpty();
    assertThat(snapshot.transfers()).isEmpty();
    assertThat(snapshot.ledger()).isEmpty();
    assertThat(snapshot.failure()).isNull();
  }

  @Test
  void preservesSuccessfulSectionsAndReportsAnyQueryFailure() {
    var transfers = CompletableFuture.<List<TransferRecord>>failedFuture(
        new IllegalStateException("database offline"));

    HistoryPageSnapshot snapshot = HistoryPageSnapshot.combine(
        CompletableFuture.<List<Trade>>completedFuture(List.of()), transfers,
        CompletableFuture.<List<AccountLedgerEntry>>completedFuture(List.of())).join();

    assertThat(snapshot.failure()).isInstanceOf(IllegalStateException.class)
        .hasMessage("database offline");
    assertThat(snapshot.transfers()).isEmpty();
  }

  @Test
  void convertsOneBasedHistoryPageToTwelveRowOffsets() {
    assertThat(HistoryPageSnapshot.offset(1)).isZero();
    assertThat(HistoryPageSnapshot.offset(2)).isEqualTo(12);
    assertThat(HistoryPageSnapshot.offset(4)).isEqualTo(36);
  }

  @Test
  void offersNextPageWhenAnySectionFilledItsBoundedPage() {
    assertThat(HistoryPageSnapshot.hasNext(12, 0, 0)).isTrue();
    assertThat(HistoryPageSnapshot.hasNext(0, 12, 0)).isTrue();
    assertThat(HistoryPageSnapshot.hasNext(0, 0, 12)).isTrue();
    assertThat(HistoryPageSnapshot.hasNext(11, 11, 11)).isFalse();
  }
}
