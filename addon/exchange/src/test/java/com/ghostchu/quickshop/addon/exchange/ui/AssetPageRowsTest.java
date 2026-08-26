package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetPageRowsTest {
  @Test
  void includesConfiguredTargetsWithZeroBalanceAndPreservesKnownBalances() {
    AssetPageRows.Merged merged = AssetPageRows.merge(
        List.of(TransferTarget.currency("default"),
            TransferTarget.item("diamond/default", "Diamond / Default")),
        List.of(new AccountAssetBalance("currency", "default",
            new BigDecimal("12.50"), new BigDecimal("1.00"))));

    assertThat(merged.rows()).containsExactly(
        new AssetPageRows.Row(TransferTarget.currency("default"),
            new BigDecimal("12.50"), new BigDecimal("1.00")),
        new AssetPageRows.Row(TransferTarget.item("diamond/default", "Diamond / Default"),
            BigDecimal.ZERO, BigDecimal.ZERO));
    assertThat(merged.securities()).isEmpty();
  }
}
