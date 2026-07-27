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

  private static UUID id(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }
}
