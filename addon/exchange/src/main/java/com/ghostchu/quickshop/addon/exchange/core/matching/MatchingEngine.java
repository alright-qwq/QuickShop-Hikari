package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class MatchingEngine {
  private final OrderBook book;
  private final LongSupplier matchSequence;
  private final Supplier<Instant> now;
  private final Supplier<UUID> tradeIds;

  public MatchingEngine(OrderBook book, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds) {
    this.book = book;
    this.matchSequence = matchSequence;
    this.now = now;
    this.tradeIds = tradeIds;
  }

  public MatchResult submit(Order incoming) {
    ArrayList<Order> makers = new ArrayList<>();
    ArrayList<Trade> trades = new ArrayList<>();
    Order taker = incoming;
    OrderSide opposite = incoming.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    while (taker.remainingQuantity() > 0) {
      Order maker = book.best(opposite).orElse(null);
      if (maker == null || !crosses(taker, maker)) {
        break;
      }
      if (maker.accountId().equals(taker.accountId())) {
        return new MatchResult(taker, makers, trades, false, true);
      }
      long quantity = Math.min(taker.remainingQuantity(), maker.remainingQuantity());
      Instant executedAt = now.get();
      taker = taker.withRemaining(taker.remainingQuantity() - quantity, executedAt);
      Order changedMaker = maker.withRemaining(maker.remainingQuantity() - quantity, executedAt);
      if (changedMaker.remainingQuantity() == 0) {
        book.cancel(maker.orderId());
      } else {
        book.replaceRemaining(changedMaker);
      }
      makers.add(changedMaker);
      UUID buyer = incoming.side() == OrderSide.BUY ? incoming.accountId() : maker.accountId();
      UUID seller = incoming.side() == OrderSide.SELL ? incoming.accountId() : maker.accountId();
      trades.add(new Trade(tradeIds.get(), incoming.marketId(), maker.orderId(), incoming.orderId(),
          buyer, seller, maker.limitPrice(), quantity,
          BigDecimal.ZERO, BigDecimal.ZERO, matchSequence.getAsLong(), executedAt));
    }
    if (taker.type() == OrderType.MARKET && taker.remainingQuantity() > 0) {
      taker = taker.withStatus(OrderStatus.CANCELLED, now.get());
    }
    boolean rested = taker.remainingQuantity() > 0 && taker.type() == OrderType.LIMIT;
    if (rested) {
      book.add(taker);
    }
    return new MatchResult(taker, makers, trades, rested, false);
  }

  private static boolean crosses(Order taker, Order maker) {
    BigDecimal boundary = taker.type() == OrderType.LIMIT
        ? taker.limitPrice() : taker.slippageBoundary();
    return taker.side() == OrderSide.BUY
        ? maker.limitPrice().compareTo(boundary) <= 0
        : maker.limitPrice().compareTo(boundary) >= 0;
  }
}
