package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketListPresenterTest {
  @Test
  void mapsQuotesIntoImmutableRowsInMarketOrder() {
    MarketListPresenter presenter = new MarketListPresenter();
    MarketQuote quote = new MarketQuote("diamond-usd", new BigDecimal("100"),
        new BigDecimal("100"), new BigDecimal("99"), new BigDecimal("101"),
        new BigDecimal("0.01"), 12, new BigDecimal("1200"), MarketStatus.OPEN, Instant.EPOCH);

    assertThat(presenter.rows(List.of(new MarketListPresenter.Entry("diamond-usd", "Diamond", quote))))
        .containsExactly(new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
            new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("0.01"), 12,
            MarketStatus.OPEN));
  }

  @Test
  void sortsMarketsByNotionalChangeOrLastPrice() {
    MarketRow diamond = new MarketRow("diamond-usd", "Diamond", new BigDecimal("100"),
        new BigDecimal("99"), new BigDecimal("101"), new BigDecimal("0.10"), 20,
        MarketStatus.OPEN);
    MarketRow iron = new MarketRow("iron-usd", "Iron", new BigDecimal("50"),
        new BigDecimal("49"), new BigDecimal("51"), new BigDecimal("-0.05"), 50,
        MarketStatus.OPEN);
    List<MarketRow> rows = List.of(diamond, iron);

    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.NOTIONAL))
        .extracting(MarketRow::marketId).containsExactly("iron-usd", "diamond-usd");
    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.CHANGE))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "iron-usd");
    assertThat(MarketListSnapshot.sorted(rows, MarketListSnapshot.SortMode.LAST))
        .extracting(MarketRow::marketId).containsExactly("diamond-usd", "iron-usd");
    assertThat(MarketListSnapshot.SortMode.NOTIONAL.next()).isEqualTo(
        MarketListSnapshot.SortMode.CHANGE);
  }
}
