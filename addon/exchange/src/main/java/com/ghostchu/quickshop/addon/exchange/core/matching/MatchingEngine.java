package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Function;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class MatchingEngine {
  private final OrderBook book;
  private final MarketRules rules;
  private final FeeCalculator fees;
  private final LongSupplier matchSequence;
  private final Supplier<Instant> now;
  private final Supplier<UUID> tradeIds;
  private final Predicate<BigDecimal> executablePrice;
  private final Function<Order, FeeRates> feeRates;
  private final BiPredicate<Order, Order> executablePair;
  private final Consumer<Trade> plannedTrade;

  public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds) {
    this(book, rules, fees, matchSequence, now, tradeIds, price -> true);
  }

  public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds,
                        Predicate<BigDecimal> executablePrice) {
    this(book, rules, fees, matchSequence, now, tradeIds, executablePrice,
        ignored -> new FeeRates(rules.makerFeeRate(), rules.takerFeeRate()));
  }

  public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds,
                        Predicate<BigDecimal> executablePrice,
                        Function<Order, FeeRates> feeRates) {
    this(book, rules, fees, matchSequence, now, tradeIds, executablePrice, feeRates,
        (incoming, maker) -> true, ignored -> { });
  }

  public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds,
                        Predicate<BigDecimal> executablePrice,
                        Function<Order, FeeRates> feeRates,
                        BiPredicate<Order, Order> executablePair) {
    this(book, rules, fees, matchSequence, now, tradeIds, executablePrice, feeRates,
        executablePair, ignored -> { });
  }

  public MatchingEngine(OrderBook book, MarketRules rules, FeeCalculator fees, LongSupplier matchSequence,
                        Supplier<Instant> now, Supplier<UUID> tradeIds,
                        Predicate<BigDecimal> executablePrice,
                        Function<Order, FeeRates> feeRates,
                        BiPredicate<Order, Order> executablePair,
                        Consumer<Trade> plannedTrade) {
    if (book == null || rules == null || fees == null || matchSequence == null || now == null
        || tradeIds == null || executablePrice == null || feeRates == null
        || executablePair == null || plannedTrade == null) {
      throw new IllegalArgumentException("matching dependencies are required");
    }
    this.book = book;
    this.rules = rules;
    this.fees = fees;
    this.matchSequence = matchSequence;
    this.now = now;
    this.tradeIds = tradeIds;
    this.executablePrice = executablePrice;
    this.feeRates = feeRates;
    this.executablePair = executablePair;
    this.plannedTrade = plannedTrade;
  }

  public MatchResult submit(Order incoming) {
    validateIncoming(incoming);
    if (!rules.marketId().equals(incoming.marketId())) {
      throw new IllegalArgumentException("order market does not match rules");
    }
    if (book.contains(incoming.orderId())) {
      throw new IllegalArgumentException("order is already resting");
    }
    if (!book.acceptsMarket(incoming.marketId())) {
      throw new IllegalArgumentException("order market does not match book");
    }
    OrderSide opposite = incoming.side() == OrderSide.BUY ? OrderSide.SELL : OrderSide.BUY;
    if (incoming.type() == OrderType.MARKET && incoming.side() == OrderSide.BUY
        && book.best(opposite).isEmpty()) {
      throw new IllegalArgumentException("market order has no executable contra liquidity");
    }
    ExecutionPlan executionPlan = planExecution(incoming, opposite);
    if (executionPlan.selfTradeRejected()) {
      return new MatchResult(incoming, List.of(), List.of(), false, true);
    }

    ArrayList<Order> makers = new ArrayList<>();
    ArrayList<Trade> trades = new ArrayList<>();
    Order taker = incoming;
    for (PlannedFill fill : executionPlan.fills()) {
      Trade trade = fill.trade();
      Order maker = fill.maker();
      taker = taker.withRemaining(taker.remainingQuantity() - trade.quantity(), trade.executedAt());
      makers.add(maker.withRemaining(
          maker.remainingQuantity() - trade.quantity(), trade.executedAt()));
      trades.add(trade);
    }
    if (taker.type() == OrderType.MARKET && taker.remainingQuantity() > 0) {
      taker = taker.withStatus(OrderStatus.CANCELLED, now.get());
    }
    boolean rested = taker.remainingQuantity() > 0 && taker.type() == OrderType.LIMIT;

    // Preflight every publication so a later replacement cannot fail after an earlier mutation.
    for (Order changedMaker : makers) {
      if (changedMaker.remainingQuantity() == 0) {
        if (!book.contains(changedMaker.orderId())) {
          throw new IllegalStateException("maker disappeared during matching");
        }
      } else {
        book.validateReplacement(changedMaker);
      }
    }

    // All validation and object construction above is complete before mutating the book.
    for (Order changedMaker : makers) {
      if (changedMaker.remainingQuantity() == 0) {
        if (book.cancel(changedMaker.orderId()).isEmpty()) {
          throw new IllegalStateException("maker disappeared during matching");
        }
      } else {
        book.replaceRemaining(changedMaker);
      }
    }
    if (rested) {
      book.add(taker);
    }
    return new MatchResult(taker, makers, trades, rested, false);
  }

  private static void validateIncoming(Order incoming) {
    if (incoming == null || incoming.orderId() == null || incoming.requestId() == null
        || incoming.accountId() == null || incoming.marketId() == null
        || incoming.marketId().isBlank() || incoming.side() == null || incoming.type() == null
        || incoming.timeInForce() == null || incoming.status() != OrderStatus.OPEN
        || incoming.remainingQuantity() != incoming.originalQuantity()
        || incoming.originalQuantity() <= 0) {
      throw new IllegalArgumentException("incoming order is not submit-eligible");
    }
    if (incoming.type() == OrderType.LIMIT
        && (incoming.limitPrice() == null || incoming.limitPrice().signum() <= 0
        || incoming.slippageBoundary() != null
        || incoming.timeInForce() != TimeInForce.GTC)) {
      throw new IllegalArgumentException("invalid limit incoming order");
    }
    if (incoming.type() == OrderType.MARKET
        && (incoming.slippageBoundary() == null || incoming.slippageBoundary().signum() <= 0
        || incoming.limitPrice() != null
        || incoming.timeInForce() != TimeInForce.IOC)) {
      throw new IllegalArgumentException("invalid market incoming order");
    }
  }

  private ExecutionPlan planExecution(Order incoming, OrderSide opposite) {
    ArrayList<PlannedFill> fills = new ArrayList<>();
    long remaining = incoming.remainingQuantity();
    for (Order maker : book.executableOrders(opposite, executablePrice)) {
      if (!maker.marketId().equals(incoming.marketId())) {
        throw new IllegalArgumentException("maker market does not match incoming order");
      }
      if (!crosses(incoming, maker)) {
        break;
      }
      if (maker.accountId().equals(incoming.accountId())) {
        return new ExecutionPlan(List.of(), remaining > 0);
      }
      if (!executablePair.test(incoming, maker)) {
        continue;
      }
      long quantity = Math.min(remaining, maker.remainingQuantity());
      Instant executedAt = now.get();
      UUID buyer = incoming.side() == OrderSide.BUY ? incoming.accountId() : maker.accountId();
      UUID seller = incoming.side() == OrderSide.SELL ? incoming.accountId() : maker.accountId();
      BigDecimal notional = maker.limitPrice().multiply(BigDecimal.valueOf(quantity));
      BigDecimal makerFee = fees.fee(notional, feeRates.apply(maker).makerRate());
      BigDecimal takerFee = fees.fee(notional, feeRates.apply(incoming).takerRate());
      Trade trade = new Trade(tradeIds.get(), incoming.marketId(), maker.orderId(), incoming.orderId(),
          buyer, seller, maker.limitPrice(), quantity,
          makerFee, takerFee, matchSequence.getAsLong(), executedAt);
      fills.add(new PlannedFill(maker, trade));
      plannedTrade.accept(trade);
      remaining -= quantity;
      if (remaining == 0) {
        break;
      }
    }
    return new ExecutionPlan(fills, false);
  }

  private static boolean crosses(Order taker, Order maker) {
    BigDecimal boundary = taker.type() == OrderType.LIMIT
        ? taker.limitPrice() : taker.slippageBoundary();
    return taker.side() == OrderSide.BUY
        ? maker.limitPrice().compareTo(boundary) <= 0
        : maker.limitPrice().compareTo(boundary) >= 0;
  }

  private record PlannedFill(Order maker, Trade trade) {
    private PlannedFill {
      if (maker == null || trade == null) {
        throw new IllegalArgumentException("planned fill is required");
      }
    }
  }

  private record ExecutionPlan(List<PlannedFill> fills, boolean selfTradeRejected) {
    private ExecutionPlan {
      fills = List.copyOf(fills);
    }
  }
}
