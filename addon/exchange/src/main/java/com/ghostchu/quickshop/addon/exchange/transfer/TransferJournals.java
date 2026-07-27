package com.ghostchu.quickshop.addon.exchange.transfer;

import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransferJournals {
  private TransferJournals() {}

  public static LedgerJournal moneyDeposit(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-deposit:journal:" + reference), "MONEY_DEPOSIT",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-deposit:liability:" + reference),
                "liability:currency:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at),
            new LedgerEntry(id("money-deposit:custody:" + reference),
                "custody:currency:" + transfer.assetId(), transfer.assetId(),
                transfer.amount().negate(), at)));
  }

  public static LedgerJournal freezeMoneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal-freeze:journal:" + reference),
        "MONEY_WITHDRAWAL_FREEZE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal-freeze:available:" + reference),
                "liability:currency:available:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal-freeze:frozen:" + reference),
                "liability:currency:frozen:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  public static LedgerJournal moneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal:journal:" + reference), "MONEY_WITHDRAWAL",
        transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal:liability:" + reference),
                "liability:currency:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal:custody:" + reference),
                "custody:currency:" + transfer.assetId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  public static LedgerJournal releaseMoneyWithdrawal(TransferRecord transfer, Instant at) {
    String reference = transfer.transferId().toString();
    return new LedgerJournal(id("money-withdrawal-release:journal:" + reference),
        "MONEY_WITHDRAWAL_RELEASE", transfer.transferId(), at, null, List.of(
            new LedgerEntry(id("money-withdrawal-release:frozen:" + reference),
                "liability:currency:frozen:" + transfer.accountId(), transfer.assetId(),
                transfer.amount().negate(), at),
            new LedgerEntry(id("money-withdrawal-release:available:" + reference),
                "liability:currency:available:" + transfer.accountId(), transfer.assetId(),
                transfer.amount(), at)));
  }

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
