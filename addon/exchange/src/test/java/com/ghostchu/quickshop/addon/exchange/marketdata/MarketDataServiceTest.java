package com.ghostchu.quickshop.addon.exchange.marketdata;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTest {
  @Test
  void exposesLatestTradeAndCurrentMinuteTotalsInQuote() {
    MarketDataService data = new MarketDataService(new CandleAggregator());
    Instant now = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, now);

    MarketQuote quote = data.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("111.00"), MarketStatus.OPEN, now);

    assertThat(quote.lastPrice()).isEqualByComparingTo("110.00");
    assertThat(quote.volume24h()).isEqualTo(3);
    assertThat(quote.notional24h()).isEqualByComparingTo("330.00");
    assertThat(quote.change24h()).isEqualByComparingTo("0");
  }

  @Test
  void rollsCandleTotalsAndOpeningPriceAcrossTwentyFourHours() {
    MarketDataService data = new MarketDataService(new CandleAggregator());
    Instant now = Instant.parse("2026-07-27T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, now.minusSeconds(24 * 60 * 60 - 1));
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, now);

    MarketQuote quote = data.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("111.00"), MarketStatus.OPEN, now);

    assertThat(quote.change24h()).isEqualByComparingTo("0.10");
    assertThat(quote.volume24h()).isEqualTo(5);
    assertThat(quote.notional24h()).isEqualByComparingTo("530.00");
  }

  @Test
  void persistsClosedMinuteWhenTheNextMinuteStarts() {
    RecordingRepository repository = new RecordingRepository();
    MarketDataService data = new MarketDataService(new CandleAggregator(), repository);
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    data.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);
    data.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, first.plusSeconds(20));

    assertThat(repository.candles).singleElement().satisfies(candle -> {
      assertThat(candle.bucketStart()).isEqualTo(Instant.parse("2026-07-26T00:00:00Z"));
      assertThat(candle.close()).isEqualByComparingTo("100.00");
      assertThat(candle.volume()).isEqualTo(2L);
    });
  }

  @Test
  void includesPersistedCandlesAfterServiceRestart() {
    RecordingRepository repository = new RecordingRepository();
    Instant first = Instant.parse("2026-07-26T00:00:40Z");
    MarketDataService beforeRestart = new MarketDataService(new CandleAggregator(), repository);
    beforeRestart.recordTrade("diamond-usd", new BigDecimal("100.00"), 2, first);
    beforeRestart.recordTrade("diamond-usd", new BigDecimal("110.00"), 3, first.plusSeconds(20));

    MarketDataService afterRestart = new MarketDataService(new CandleAggregator(), repository);
    afterRestart.recordTrade("diamond-usd", new BigDecimal("120.00"), 4, first.plusSeconds(80));

    MarketQuote quote = afterRestart.quote("diamond-usd", new BigDecimal("100.00"),
        new BigDecimal("99.00"), new BigDecimal("121.00"), MarketStatus.OPEN,
        first.plusSeconds(80));
    assertThat(quote.volume24h()).isEqualTo(6L);
    assertThat(quote.notional24h()).isEqualByComparingTo("680.00");
    assertThat(quote.change24h()).isEqualByComparingTo("0.20");
  }

  private static final class RecordingRepository implements ExchangeRepository {
    private final List<Candle> candles = new ArrayList<>();

    @Override
    public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
      throw new UnsupportedOperationException("not used by the market data service");
    }

    @Override
    public void upsertCandle(Candle candle) {
      candles.add(candle);
    }

    @Override
    public List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive) {
      return candles.stream().filter(candle -> candle.marketId().equals(marketId)
          && !candle.bucketStart().isBefore(fromInclusive)
          && candle.bucketStart().isBefore(toExclusive)).toList();
    }
  }
}
