package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.marketdata.CandleAggregator;
import com.ghostchu.quickshop.addon.exchange.marketdata.MarketDataService;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.PersistedOrder;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExchangeViewServiceTest {
  @Test
  void exposesConfiguredTransferTargetsEvenBeforeAnAccountHasBalances() {
    List<TransferTarget> targets = List.of(
        TransferTarget.currency("default"),
        TransferTarget.item("diamond/default", "Diamond / Default"));
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run,
        new RecordingRepository(), targets);

    assertThat(views.transferTargets()).containsExactlyElementsOf(targets);
    assertThatThrownBy(() -> views.transferTargets().add(TransferTarget.currency("other")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void loadsOnlyTheRequestedAccountOrderPageOffThePlayerThread() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    List<Order> orders = views.accountOrders(accountId, 36, 72).join();

    assertThat(repository.accountId).isEqualTo(accountId);
    assertThat(repository.limit).isEqualTo(36);
    assertThat(repository.offset).isEqualTo(72);
    assertThat(orders).extracting(Order::orderId).containsExactly(repository.order.orderId());
    assertThat(repository.calls).hasValue(1);
  }

  @Test
  void loadsOnlyTheRequestedAccountAssetsOffThePlayerThread() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    List<AccountAssetBalance> assets = views.accountAssets(accountId).join();

    assertThat(repository.assetAccountId).isEqualTo(accountId);
    assertThat(assets).containsExactly(new AccountAssetBalance("currency", "default",
        new BigDecimal("12.50"), new BigDecimal("1.00")));
  }

  @Test
  void loadsOnlyTheRequestedAccountTradePage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountTrades(accountId, 36, 36).join()).containsExactly(repository.trade);
    assertThat(repository.tradeAccountId).isEqualTo(accountId);
    assertThat(repository.tradeLimit).isEqualTo(36);
    assertThat(repository.tradeOffset).isEqualTo(36);
  }

  @Test
  void loadsTheRequestedAccountTransferPage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountTransfers(accountId, 36, 0).join()).containsExactly(repository.transfer);
    assertThat(repository.transferAccountId).isEqualTo(accountId);
  }

  @Test
  void loadsOnlyTheRequestedAccountLedgerPage() {
    UUID accountId = UUID.randomUUID();
    RecordingRepository repository = new RecordingRepository();
    ExchangeViewService views = new ExchangeViewService(
        java.util.Map.of(), new MarketDataService(new CandleAggregator()), Runnable::run, repository);

    assertThat(views.accountLedger(accountId, 18, 36).join())
        .containsExactly(repository.ledgerEntry);
    assertThat(repository.ledgerAccountId).isEqualTo(accountId);
    assertThat(repository.ledgerLimit).isEqualTo(18);
    assertThat(repository.ledgerOffset).isEqualTo(36);
  }

  private static final class RecordingRepository implements ExchangeRepository {
    private final Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "diamond-usd",
        UUID.randomUUID(), OrderSide.BUY, OrderType.LIMIT, TimeInForce.GTC,
        new BigDecimal("100.00"), null, 10, 10, OrderStatus.OPEN, 1, 1, 1,
        Instant.EPOCH, Instant.EPOCH);
    private final AtomicInteger calls = new AtomicInteger();
    private UUID accountId;
    private int limit;
    private int offset;
    private UUID assetAccountId;
    private final Trade trade = new Trade(UUID.randomUUID(), "diamond-usd", UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"),
        2, new BigDecimal("0.10"), new BigDecimal("0.20"), 1, Instant.EPOCH);
    private UUID tradeAccountId;
    private int tradeLimit;
    private int tradeOffset;
    private final TransferRecord transfer = new TransferRecord(UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), TransferType.MONEY_DEPOSIT, "default", new BigDecimal("5.00"),
        TransferStatus.REVIEW_REQUIRED, null, "review", Instant.EPOCH, Instant.EPOCH, 1);
    private UUID transferAccountId;
    private final AccountLedgerEntry ledgerEntry = new AccountLedgerEntry(
        UUID.randomUUID(), "MONEY_DEPOSIT", UUID.randomUUID(), "default",
        new BigDecimal("5.00"), Instant.EPOCH);
    private UUID ledgerAccountId;
    private int ledgerLimit;
    private int ledgerOffset;

    @Override
    public List<AccountLedgerEntry> accountLedgerEntries(UUID accountId, int limit, int offset) {
      ledgerAccountId = accountId;
      ledgerLimit = limit;
      ledgerOffset = offset;
      return List.of(ledgerEntry);
    }

    @Override
    public List<TransferRecord> accountTransfers(UUID accountId, int limit, int offset) {
      transferAccountId = accountId;
      return List.of(transfer);
    }

    @Override
    public List<Trade> accountTrades(UUID accountId, int limit, int offset) {
      tradeAccountId = accountId;
      tradeLimit = limit;
      tradeOffset = offset;
      return List.of(trade);
    }

    @Override
    public List<AccountAssetBalance> accountAssets(UUID accountId) {
      assetAccountId = accountId;
      return List.of(new AccountAssetBalance("currency", "default",
          new BigDecimal("12.50"), new BigDecimal("1.00")));
    }

    @Override
    public List<PersistedOrder> accountOpenOrders(UUID accountId, int limit, int offset) {
      this.accountId = accountId;
      this.limit = limit;
      this.offset = offset;
      calls.incrementAndGet();
      return List.of(new PersistedOrder(order, BigDecimal.ZERO, 0, 1));
    }

    @Override
    public <T> T inTransaction(TransactionWork<T> work) {
      throw new AssertionError("account page must use the paged read method");
    }
  }
}
