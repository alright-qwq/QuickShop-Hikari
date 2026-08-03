package com.ghostchu.quickshop.addon.exchange.core.matching;

import com.ghostchu.quickshop.addon.exchange.core.book.OrderBook;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketRules;
import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class ReservationCalculator {
  private final FeeCalculator fees;

  public ReservationCalculator(FeeCalculator fees) {
    if (fees == null) {
      throw new IllegalArgumentException("fee calculator is required");
    }
    this.fees = fees;
  }

  public Reservation reserve(Order order, MarketRules rules) {
    validate(order, rules);
    if (order.side() == OrderSide.SELL) {
      return new Reservation(BigDecimal.ZERO, order.remainingQuantity());
    }
    if (order.type() == OrderType.MARKET) {
      throw new IllegalArgumentException("market buy reservation requires executable depth");
    }
    BigDecimal quantity = BigDecimal.valueOf(order.remainingQuantity());
    BigDecimal notional = order.limitPrice().multiply(quantity);
    BigDecimal maximumFeeRate = rules.makerFeeRate().max(rules.takerFeeRate());
    BigDecimal maximumPerFillFees = fees.fee(order.limitPrice(), maximumFeeRate).multiply(quantity);
    return new Reservation(notional.add(maximumPerFillFees), 0);
  }

  public Reservation reserve(Order order, MarketRules rules, OrderBook book,
                             Predicate<BigDecimal> executablePrice) {
    return reserve(order, rules, book, executablePrice, (incoming, maker) -> true);
  }

  public Reservation reserve(Order order, MarketRules rules, OrderBook book,
                             Predicate<BigDecimal> executablePrice,
                             BiPredicate<Order, Order> executablePair) {
    validate(order, rules);
    if (order.side() == OrderSide.SELL || order.type() == OrderType.LIMIT) {
      return reserve(order, rules);
    }
    if (book == null || executablePrice == null || executablePair == null) {
      throw new IllegalArgumentException("book and executable guards are required");
    }

    long remaining = order.remainingQuantity();
    BigDecimal notional = BigDecimal.ZERO;
    BigDecimal feesForFills = BigDecimal.ZERO;
    for (Order maker : book.executableOrders(OrderSide.SELL, executablePrice)) {
      validateExecutableMaker(maker, order);
      if (maker.limitPrice().compareTo(order.slippageBoundary()) > 0) {
        break;
      }
      if (!executablePair.test(order, maker)) {
        continue;
      }
      long quantity = Math.min(remaining, maker.remainingQuantity());
      BigDecimal fillNotional = maker.limitPrice().multiply(BigDecimal.valueOf(quantity));
      notional = notional.add(fillNotional);
      feesForFills = feesForFills.add(fees.fee(fillNotional, rules.takerFeeRate()));
      remaining -= quantity;
      if (remaining == 0) {
        break;
      }
    }
    return new Reservation(notional.add(feesForFills), 0);
  }

  public Reservation reserve(Order order, MarketRules rules, List<Trade> trades) {
    validate(order, rules);
    if (order.side() == OrderSide.SELL || order.type() == OrderType.LIMIT) {
      return reserve(order, rules);
    }
    if (trades == null) {
      throw new IllegalArgumentException("market buy trades are required");
    }
    BigDecimal frozen = BigDecimal.ZERO;
    long quantity = 0;
    for (Trade trade : trades) {
      if (trade == null || !order.marketId().equals(trade.marketId())
          || !order.accountId().equals(trade.buyerAccountId())
          || !order.orderId().equals(trade.takerOrderId())) {
        throw new IllegalArgumentException("trade does not belong to market buy order");
      }
      BigDecimal notional = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));
      frozen = frozen.add(notional).add(trade.takerFee());
      quantity = Math.addExact(quantity, trade.quantity());
    }
    if (quantity > order.remainingQuantity()) {
      throw new IllegalArgumentException("market buy trades exceed order quantity");
    }
    return new Reservation(frozen, 0);
  }

  private static void validate(Order order, MarketRules rules) {
    if (order == null || rules == null) {
      throw new IllegalArgumentException("order and market rules are required");
    }
    if (!rules.marketId().equals(order.marketId())) {
      throw new IllegalArgumentException("order market does not match rules");
    }
  }

  private static void validateExecutableMaker(Order maker, Order incoming) {
    if (maker == null || maker.side() != OrderSide.SELL || maker.type() != OrderType.LIMIT
        || maker.limitPrice() == null || maker.limitPrice().signum() <= 0
        || maker.remainingQuantity() <= 0 || !maker.marketId().equals(incoming.marketId())) {
      throw new IllegalArgumentException("invalid executable maker depth");
    }
  }
}
