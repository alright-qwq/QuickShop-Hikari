package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Merges configured transfer targets with persisted account balances. */
final class AssetPageRows {
  private AssetPageRows() {}

  static List<Row> merge(List<TransferTarget> targets, List<AccountAssetBalance> balances) {
    Map<String, AccountAssetBalance> remaining = new LinkedHashMap<>();
    for (AccountAssetBalance balance : balances) {
      remaining.put(key(balance.kind(), balance.assetId()), balance);
    }
    List<Row> rows = new ArrayList<>();
    for (TransferTarget target : targets) {
      AccountAssetBalance balance = remaining.remove(key(target));
      rows.add(balance == null
          ? new Row(target, BigDecimal.ZERO, BigDecimal.ZERO)
          : new Row(target, balance.available(), balance.frozen()));
    }
    for (AccountAssetBalance balance : remaining.values()) {
      TransferTarget target = balance.kind().equals("currency")
          ? TransferTarget.currency(balance.assetId())
          : TransferTarget.item(balance.assetId(), balance.assetId());
      rows.add(new Row(target, balance.available(), balance.frozen()));
    }
    return List.copyOf(rows);
  }

  private static String key(TransferTarget target) {
    return key(target.kind() == TransferTarget.Kind.CURRENCY ? "currency" : "item",
        target.assetId());
  }

  private static String key(String kind, String assetId) {
    return kind + ':' + assetId;
  }

  record Row(TransferTarget target, BigDecimal available, BigDecimal frozen) {
    Row {
      if (target == null || available == null || frozen == null
          || available.signum() < 0 || frozen.signum() < 0) {
        throw new IllegalArgumentException("invalid asset page row");
      }
    }
  }
}
