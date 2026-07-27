package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.repository.CurrencyBalance;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.InsufficientAssetsException;
import com.ghostchu.quickshop.addon.exchange.repository.ItemBalance;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcExchangeRepository implements ExchangeRepository {
  private final ConnectionProvider connections;
  private final SqlDialect dialect;
  private final TableNames tables;

  public JdbcExchangeRepository(
      ConnectionProvider connections, SqlDialect dialect, TableNames tables) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.tables = Objects.requireNonNull(tables, "tables");
  }

  @Override
  public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
    try (Connection connection = connections.open()) {
      if (dialect == SqlDialect.SQLITE) {
        try (Statement begin = connection.createStatement()) {
          begin.execute("BEGIN IMMEDIATE");
        }
      } else {
        connection.setAutoCommit(false);
      }
      try {
        T result = work.apply(new JdbcTransaction(connection, dialect, tables));
        if (dialect == SqlDialect.SQLITE) {
          try (Statement commit = connection.createStatement()) {
            commit.execute("COMMIT");
          }
        } else {
          connection.commit();
        }
        return result;
      } catch (SQLException | RuntimeException failure) {
        if (dialect == SqlDialect.SQLITE) {
          try (Statement rollback = connection.createStatement()) {
            rollback.execute("ROLLBACK");
          } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
          }
        } else {
          try {
            connection.rollback();
          } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
          }
        }
        throw failure;
      }
    }
  }

  private static final class JdbcTransaction implements ExchangeTransaction {
    private static final String LEDGER_SAVEPOINT = "exchange_ledger_append";

    private final Connection connection;
    private final SqlDialect dialect;
    private final TableNames tables;

    private JdbcTransaction(Connection connection, SqlDialect dialect, TableNames tables) {
      this.connection = connection;
      this.dialect = dialect;
      this.tables = tables;
    }

    @Override
    public CurrencyBalance currency(UUID accountId, String currencyId) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          insertIgnorePrefix() + tables.accounts()
              + " (account_id,currency_id,available,frozen,version) VALUES (?,?,?,?,0)")) {
        insert.setString(1, accountId.toString());
        insert.setString(2, currencyId);
        writeDecimal(insert, 3, BigDecimal.ZERO);
        writeDecimal(insert, 4, BigDecimal.ZERO);
        insert.executeUpdate();
      }
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT available,frozen,version FROM " + tables.accounts()
              + " WHERE account_id=? AND currency_id=?" + dialect.forUpdate())) {
        select.setString(1, accountId.toString());
        select.setString(2, currencyId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("currency balance was not created");
          }
          return new CurrencyBalance(accountId, currencyId,
              readDecimal(result, "available"), readDecimal(result, "frozen"),
              result.getLong("version"));
        }
      }
    }

    @Override
    public ItemBalance inventory(UUID accountId, String marketId) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          insertIgnorePrefix() + tables.inventory()
              + " (account_id,market_id,available_quantity,frozen_quantity,version)"
              + " VALUES (?,?,0,0,0)")) {
        insert.setString(1, accountId.toString());
        insert.setString(2, marketId);
        insert.executeUpdate();
      }
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT available_quantity,frozen_quantity,version FROM " + tables.inventory()
              + " WHERE account_id=? AND market_id=?" + dialect.forUpdate())) {
        select.setString(1, accountId.toString());
        select.setString(2, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("item balance was not created");
          }
          return new ItemBalance(accountId, marketId,
              result.getLong("available_quantity"), result.getLong("frozen_quantity"),
              result.getLong("version"));
        }
      }
    }

    @Override
    public void creditAvailableCurrency(UUID accountId, String currencyId, BigDecimal amount)
        throws SQLException {
      requirePositive(amount);
      CurrencyBalance before = currency(accountId, currencyId);
      updateCurrency(before, before.available().add(amount), before.frozen());
    }

    @Override
    public void freezeCurrency(UUID accountId, String currencyId, BigDecimal amount)
        throws SQLException {
      requirePositive(amount);
      CurrencyBalance before = currency(accountId, currencyId);
      requireCurrencySource(before.available(), amount);
      updateCurrency(before, before.available().subtract(amount), before.frozen().add(amount));
    }

    @Override
    public void releaseCurrency(UUID accountId, String currencyId, BigDecimal amount)
        throws SQLException {
      requirePositive(amount);
      CurrencyBalance before = currency(accountId, currencyId);
      requireCurrencySource(before.frozen(), amount);
      updateCurrency(before, before.available().add(amount), before.frozen().subtract(amount));
    }

    @Override
    public void consumeFrozenCurrency(UUID accountId, String currencyId, BigDecimal amount)
        throws SQLException {
      requirePositive(amount);
      CurrencyBalance before = currency(accountId, currencyId);
      requireCurrencySource(before.frozen(), amount);
      updateCurrency(before, before.available(), before.frozen().subtract(amount));
    }

    @Override
    public void creditAvailableItems(UUID accountId, String marketId, long quantity)
        throws SQLException {
      requirePositive(quantity);
      ItemBalance before = inventory(accountId, marketId);
      updateItems(before, Math.addExact(before.availableQuantity(), quantity),
          before.frozenQuantity());
    }

    @Override
    public void freezeItems(UUID accountId, String marketId, long quantity) throws SQLException {
      requirePositive(quantity);
      ItemBalance before = inventory(accountId, marketId);
      requireItemSource(before.availableQuantity(), quantity);
      updateItems(before, before.availableQuantity() - quantity,
          Math.addExact(before.frozenQuantity(), quantity));
    }

    @Override
    public void releaseItems(UUID accountId, String marketId, long quantity) throws SQLException {
      requirePositive(quantity);
      ItemBalance before = inventory(accountId, marketId);
      requireItemSource(before.frozenQuantity(), quantity);
      updateItems(before, Math.addExact(before.availableQuantity(), quantity),
          before.frozenQuantity() - quantity);
    }

    @Override
    public void consumeFrozenItems(UUID accountId, String marketId, long quantity)
        throws SQLException {
      requirePositive(quantity);
      ItemBalance before = inventory(accountId, marketId);
      requireItemSource(before.frozenQuantity(), quantity);
      updateItems(before, before.availableQuantity(), before.frozenQuantity() - quantity);
    }

    @Override
    public Optional<StoredRequestResult> requestResult(UUID accountId, UUID requestId)
        throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT operation,result_payload FROM " + tables.requestResults()
              + " WHERE account_id=? AND request_id=?")) {
        select.setString(1, accountId.toString());
        select.setString(2, requestId.toString());
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            return Optional.empty();
          }
          return Optional.of(new StoredRequestResult(accountId, requestId,
              result.getString("operation"), result.getString("result_payload")));
        }
      }
    }

    @Override
    public void putRequestResult(StoredRequestResult result) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.requestResults()
              + " (account_id,request_id,operation,result_payload,created_at) VALUES (?,?,?,?,?)")) {
        insert.setString(1, result.accountId().toString());
        insert.setString(2, result.requestId().toString());
        insert.setString(3, result.operation());
        insert.setString(4, result.payload());
        insert.setLong(5, Instant.now().toEpochMilli());
        insert.executeUpdate();
      }
    }

    @Override
    public MarketState marketState(String marketId) throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT status,priority_sequence,match_sequence,reference_price,last_price,"
              + "halted_until,discovery_quantity,circuit_breaker_level,version FROM "
              + tables.marketState()
              + " WHERE market_id=?" + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("market state does not exist: " + marketId);
          }
          Long haltedUntil = nullableLong(result, "halted_until");
          return new MarketState(marketId, MarketStatus.valueOf(result.getString("status")),
              result.getLong("priority_sequence"), result.getLong("match_sequence"),
              readDecimal(result, "reference_price"), readNullableDecimal(result, "last_price"),
              haltedUntil == null ? null : Instant.ofEpochMilli(haltedUntil),
              nullableLong(result, "discovery_quantity"),
              nullableInteger(result, "circuit_breaker_level"),
              result.getLong("version"));
        }
      }
    }

    @Override
    public List<PersistedOrder> openOrders(String marketId) throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT order_id,request_id,market_id,account_id,side,order_type,time_in_force,"
              + "limit_price,slippage_boundary,original_quantity,remaining_quantity,status,"
              + "priority_sequence,config_version,fee_version,reserved_currency,"
              + "reserved_quantity,created_at,updated_at,version FROM " + tables.orders()
              + " WHERE market_id=? AND status IN ('OPEN','PARTIALLY_FILLED')"
              + " ORDER BY priority_sequence" + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          ArrayList<PersistedOrder> orders = new ArrayList<>();
          while (result.next()) {
            Order order = new Order(UUID.fromString(result.getString("order_id")),
                UUID.fromString(result.getString("request_id")), result.getString("market_id"),
                UUID.fromString(result.getString("account_id")),
                OrderSide.valueOf(result.getString("side")),
                OrderType.valueOf(result.getString("order_type")),
                TimeInForce.valueOf(result.getString("time_in_force")),
                readNullableDecimal(result, "limit_price"),
                readNullableDecimal(result, "slippage_boundary"),
                result.getLong("original_quantity"), result.getLong("remaining_quantity"),
                OrderStatus.valueOf(result.getString("status")),
                result.getLong("priority_sequence"), result.getLong("config_version"),
                result.getLong("fee_version"),
                Instant.ofEpochMilli(result.getLong("created_at")),
                Instant.ofEpochMilli(result.getLong("updated_at")));
            orders.add(new PersistedOrder(order, readDecimal(result, "reserved_currency"),
                result.getLong("reserved_quantity"), result.getLong("version")));
          }
          return List.copyOf(orders);
        }
      }
    }

    @Override
    public void updateMarketState(MarketState state, long expectedVersion) throws SQLException {
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.marketState()
              + " SET status=?,priority_sequence=?,match_sequence=?,reference_price=?,"
              + "last_price=?,halted_until=?,discovery_quantity=?,circuit_breaker_level=?,"
              + "version=version+1"
              + " WHERE market_id=? AND version=?")) {
        update.setString(1, state.status().name());
        update.setLong(2, state.prioritySequence());
        update.setLong(3, state.matchSequence());
        writeDecimal(update, 4, state.referencePrice());
        writeNullableDecimal(update, 5, state.lastPrice());
        if (state.haltedUntil() == null) {
          update.setNull(6, Types.BIGINT);
        } else {
          update.setLong(6, state.haltedUntil().toEpochMilli());
        }
        if (state.discoveryQuantity() == null) {
          update.setNull(7, Types.BIGINT);
        } else {
          update.setLong(7, state.discoveryQuantity());
        }
        if (state.circuitBreakerLevel() == null) {
          update.setNull(8, Types.INTEGER);
        } else {
          update.setInt(8, state.circuitBreakerLevel());
        }
        update.setString(9, state.marketId());
        update.setLong(10, expectedVersion);
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("market state version changed");
        }
      }
    }

    @Override
    public void insertHighAlert(UUID alertId, String marketId, String alertType,
                                String payload, Instant createdAt) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.auditAlerts()
              + " (alert_id,market_id,account_id,alert_type,severity,payload,created_at,"
              + "acknowledged_at) VALUES (?,?,?,?,?,?,?,?)")) {
        insert.setString(1, alertId.toString());
        insert.setString(2, marketId);
        insert.setNull(3, Types.VARCHAR);
        insert.setString(4, alertType);
        insert.setString(5, "HIGH");
        insert.setString(6, payload);
        insert.setLong(7, createdAt.toEpochMilli());
        insert.setNull(8, Types.BIGINT);
        insert.executeUpdate();
      }
    }

    @Override
    public void insertOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity)
        throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.orders()
              + " (order_id,request_id,market_id,account_id,side,order_type,time_in_force,"
              + "limit_price,slippage_boundary,original_quantity,remaining_quantity,status,"
              + "priority_sequence,config_version,fee_version,reserved_currency,reserved_quantity,"
              + "created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)")) {
        writeOrder(insert, order, reservedCurrency, reservedQuantity);
        insert.executeUpdate();
      }
    }

    @Override
    public void updateOrder(Order order, BigDecimal reservedCurrency, long reservedQuantity,
                            long expectedVersion) throws SQLException {
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.orders()
              + " SET request_id=?,market_id=?,account_id=?,side=?,order_type=?,time_in_force=?,"
              + "limit_price=?,slippage_boundary=?,original_quantity=?,remaining_quantity=?,"
              + "status=?,priority_sequence=?,config_version=?,fee_version=?,reserved_currency=?,"
              + "reserved_quantity=?,created_at=?,updated_at=?,version=version+1"
              + " WHERE order_id=? AND version=?")) {
        writeOrderForUpdate(update, order, reservedCurrency, reservedQuantity);
        update.setString(19, order.orderId().toString());
        update.setLong(20, expectedVersion);
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("order version changed");
        }
      }
    }

    @Override
    public void insertTrade(Trade trade) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.trades()
              + " (trade_id,market_id,maker_order_id,taker_order_id,buyer_account_id,"
              + "seller_account_id,price,quantity,maker_fee,taker_fee,match_sequence,executed_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
        insert.setString(1, trade.tradeId().toString());
        insert.setString(2, trade.marketId());
        insert.setString(3, trade.makerOrderId().toString());
        insert.setString(4, trade.takerOrderId().toString());
        insert.setString(5, trade.buyerAccountId().toString());
        insert.setString(6, trade.sellerAccountId().toString());
        writeDecimal(insert, 7, trade.price());
        insert.setLong(8, trade.quantity());
        writeDecimal(insert, 9, trade.makerFee());
        writeDecimal(insert, 10, trade.takerFee());
        insert.setLong(11, trade.matchSequence());
        insert.setLong(12, trade.executedAt().toEpochMilli());
        insert.executeUpdate();
      }
    }

    @Override
    public void appendJournal(LedgerJournal journal) throws SQLException {
      executeTransactionControl("SAVEPOINT " + LEDGER_SAVEPOINT);
      try {
        insertJournal(journal);
        insertEntries(journal);
        executeTransactionControl("RELEASE SAVEPOINT " + LEDGER_SAVEPOINT);
      } catch (SQLException | RuntimeException failure) {
        try {
          executeTransactionControl("ROLLBACK TO SAVEPOINT " + LEDGER_SAVEPOINT);
          executeTransactionControl("RELEASE SAVEPOINT " + LEDGER_SAVEPOINT);
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    }

    private void insertJournal(LedgerJournal journal) throws SQLException {
      try (PreparedStatement insertJournal = connection.prepareStatement(
          "INSERT INTO " + tables.journals()
              + " (journal_id,journal_type,reference_id,created_at,reversal_of)"
              + " VALUES (?,?,?,?,?)")) {
        insertJournal.setString(1, journal.journalId().toString());
        insertJournal.setString(2, journal.journalType());
        insertJournal.setString(3, journal.referenceId().toString());
        insertJournal.setLong(4, journal.createdAt().toEpochMilli());
        if (journal.reversalOf() == null) {
          insertJournal.setNull(5, Types.VARCHAR);
        } else {
          insertJournal.setString(5, journal.reversalOf().toString());
        }
        insertJournal.executeUpdate();
      }
    }

    private void insertEntries(LedgerJournal journal) throws SQLException {
      try (PreparedStatement insertEntry = connection.prepareStatement(
          "INSERT INTO " + tables.entries()
              + " (entry_id,journal_id,account_code,asset_id,amount,created_at)"
              + " VALUES (?,?,?,?,?,?)")) {
        for (LedgerEntry entry : journal.entries()) {
          insertEntry.setString(1, entry.entryId().toString());
          insertEntry.setString(2, journal.journalId().toString());
          insertEntry.setString(3, entry.accountCode());
          insertEntry.setString(4, entry.assetId());
          writeDecimal(insertEntry, 5, entry.amount());
          insertEntry.setLong(6, entry.createdAt().toEpochMilli());
          insertEntry.addBatch();
        }
        insertEntry.executeBatch();
      }
    }

    private void executeTransactionControl(String sql) throws SQLException {
      try (Statement statement = connection.createStatement()) {
        statement.execute(sql);
      }
    }

    private void updateCurrency(
        CurrencyBalance before, BigDecimal available, BigDecimal frozen) throws SQLException {
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.accounts()
              + " SET available=?,frozen=?,version=version+1"
              + " WHERE account_id=? AND currency_id=? AND version=?")) {
        writeDecimal(update, 1, available);
        writeDecimal(update, 2, frozen);
        update.setString(3, before.accountId().toString());
        update.setString(4, before.currencyId());
        update.setLong(5, before.version());
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("currency version changed");
        }
      }
    }

    private void updateItems(ItemBalance before, long available, long frozen) throws SQLException {
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.inventory()
              + " SET available_quantity=?,frozen_quantity=?,version=version+1"
              + " WHERE account_id=? AND market_id=? AND version=?")) {
        update.setLong(1, available);
        update.setLong(2, frozen);
        update.setString(3, before.accountId().toString());
        update.setString(4, before.marketId());
        update.setLong(5, before.version());
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("item version changed");
        }
      }
    }

    private String insertIgnorePrefix() {
      return dialect == SqlDialect.SQLITE ? "INSERT OR IGNORE INTO " : "INSERT IGNORE INTO ";
    }

    private void writeDecimal(PreparedStatement statement, int index, BigDecimal value)
        throws SQLException {
      if (dialect == SqlDialect.SQLITE) {
        statement.setString(index, value.toPlainString());
      } else {
        statement.setBigDecimal(index, value);
      }
    }

    private void writeNullableDecimal(
        PreparedStatement statement, int index, BigDecimal value) throws SQLException {
      if (value == null) {
        statement.setNull(index, Types.DECIMAL);
      } else {
        writeDecimal(statement, index, value);
      }
    }

    private BigDecimal readDecimal(ResultSet result, String column) throws SQLException {
      return dialect == SqlDialect.SQLITE
          ? new BigDecimal(result.getString(column))
          : result.getBigDecimal(column);
    }

    private BigDecimal readNullableDecimal(ResultSet result, String column) throws SQLException {
      String value = result.getString(column);
      return value == null ? null : new BigDecimal(value);
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
      long value = result.getLong(column);
      return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
      int value = result.getInt(column);
      return result.wasNull() ? null : value;
    }

    private void writeOrder(
        PreparedStatement statement, Order order, BigDecimal reservedCurrency,
        long reservedQuantity) throws SQLException {
      statement.setString(1, order.orderId().toString());
      writeOrderForUpdate(statement, order, reservedCurrency, reservedQuantity, 2);
    }

    private void writeOrderForUpdate(
        PreparedStatement statement, Order order, BigDecimal reservedCurrency,
        long reservedQuantity) throws SQLException {
      writeOrderForUpdate(statement, order, reservedCurrency, reservedQuantity, 1);
    }

    private void writeOrderForUpdate(
        PreparedStatement statement, Order order, BigDecimal reservedCurrency,
        long reservedQuantity, int firstIndex) throws SQLException {
      statement.setString(firstIndex, order.requestId().toString());
      statement.setString(firstIndex + 1, order.marketId());
      statement.setString(firstIndex + 2, order.accountId().toString());
      statement.setString(firstIndex + 3, order.side().name());
      statement.setString(firstIndex + 4, order.type().name());
      statement.setString(firstIndex + 5, order.timeInForce().name());
      writeNullableDecimal(statement, firstIndex + 6, order.limitPrice());
      writeNullableDecimal(statement, firstIndex + 7, order.slippageBoundary());
      statement.setLong(firstIndex + 8, order.originalQuantity());
      statement.setLong(firstIndex + 9, order.remainingQuantity());
      statement.setString(firstIndex + 10, order.status().name());
      statement.setLong(firstIndex + 11, order.prioritySequence());
      statement.setLong(firstIndex + 12, order.configVersion());
      statement.setLong(firstIndex + 13, order.feeVersion());
      writeDecimal(statement, firstIndex + 14, reservedCurrency);
      statement.setLong(firstIndex + 15, reservedQuantity);
      statement.setLong(firstIndex + 16, order.createdAt().toEpochMilli());
      statement.setLong(firstIndex + 17, order.updatedAt().toEpochMilli());
    }

    private static void requirePositive(BigDecimal amount) {
      if (amount == null || amount.signum() <= 0) {
        throw new IllegalArgumentException("amount must be positive");
      }
    }

    private static void requirePositive(long quantity) {
      if (quantity <= 0) {
        throw new IllegalArgumentException("quantity must be positive");
      }
    }

    private static void requireCurrencySource(BigDecimal source, BigDecimal amount) {
      if (source.compareTo(amount) < 0) {
        throw new InsufficientAssetsException("currency");
      }
    }

    private static void requireItemSource(long source, long quantity) {
      if (source < quantity) {
        throw new InsufficientAssetsException("items");
      }
    }
  }
}
