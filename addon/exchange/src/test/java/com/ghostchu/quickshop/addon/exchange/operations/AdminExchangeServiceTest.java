package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.service.ExchangeServiceFixture;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.OrderRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminExchangeServiceTest {
  @Test
  void forceCancelReturnsReservedCurrencyAndAppendsAnAuditRecord() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID buyer = fixture.accountWithCurrency("500.00");
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), buyer, fixture.rules().marketId(), OrderSide.BUY, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    UUID actor = UUID.randomUUID();
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));

    admin.forceCancel(actor, UUID.randomUUID(), fixture.rules().marketId(), receipt.orderId(),
        "suspected abuse");

    assertThat(fixture.orderStatus(receipt.orderId())).isEqualTo("CANCELLED");
    assertThat(fixture.availableCurrency(buyer)).isEqualByComparingTo("500.00");
    assertThat(fixture.frozenCurrency(buyer)).isZero();
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.actorId()).isEqualTo(actor);
          assertThat(record.action()).isEqualTo("FORCE_CANCEL_ORDER");
          assertThat(record.targetId()).isEqualTo(receipt.orderId().toString());
          assertThat(record.reason()).isEqualTo("suspected abuse");
          assertThat(record.beforeState()).contains("OPEN");
          assertThat(record.afterState()).contains("CANCELLED");
        });
    assertThat(fixture.tradeCount()).isZero();
  }

  @Test
  void forceCancelReturnsReservedItemsForSellOrders() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    UUID seller = fixture.accountWithItems(3);
    OrderReceipt receipt = fixture.service().place(new OrderRequest(
        UUID.randomUUID(), seller, fixture.rules().marketId(), OrderSide.SELL, "LIMIT",
        new BigDecimal("100.00"), null, 2));
    AdminExchangeService admin = new AdminExchangeService(
        Map.of(fixture.rules().marketId(), fixture.service()));

    admin.forceCancel(UUID.randomUUID(), UUID.randomUUID(), fixture.rules().marketId(),
        receipt.orderId(), "suspected abuse");

    assertThat(fixture.orderStatus(receipt.orderId())).isEqualTo("CANCELLED");
    assertThat(fixture.availableItems(seller)).isEqualTo(3);
    assertThat(fixture.frozenItems(seller)).isZero();
  }

  @Test
  void pausesAndResumesAMarketWithImmutableAuditRecords() throws Exception {
    ExchangeServiceFixture fixture = ExchangeServiceFixture.sqlite();
    AdminExchangeService admin = new AdminExchangeService(fixture.repository(),
        Map.of(fixture.rules().marketId(), fixture.service()));
    UUID actor = UUID.randomUUID();

    admin.pauseMarket(actor, fixture.rules().marketId(), "scheduled maintenance");
    assertThat(fixture.marketStatus()).isEqualTo("PAUSED");
    admin.resumeMarket(actor, fixture.rules().marketId(), "maintenance complete");

    assertThat(fixture.marketStatus()).isEqualTo("OPEN");
    assertThat(fixture.repository().auditRecords(Instant.EPOCH, Instant.now().plusSeconds(1)))
        .extracting(AuditRecord::action)
        .containsExactly("PAUSE_MARKET", "RESUME_MARKET");
  }
}
