package com.ghostchu.quickshop.addon.exchange.persistence;

import com.ghostchu.quickshop.addon.exchange.core.model.Order;
import com.ghostchu.quickshop.addon.exchange.config.MarketConfigurationPersistence;
import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.FeeRates;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderStatus;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import com.ghostchu.quickshop.addon.exchange.core.model.TimeInForce;
import com.ghostchu.quickshop.addon.exchange.core.model.Trade;
import com.ghostchu.quickshop.addon.exchange.core.trust.AdjustmentType;
import com.ghostchu.quickshop.addon.exchange.core.trust.LimitReason;
import com.ghostchu.quickshop.addon.exchange.core.trust.LiquidityTier;
import com.ghostchu.quickshop.addon.exchange.core.trust.TradeInfluence;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceAdjustment;
import com.ghostchu.quickshop.addon.exchange.core.trust.TrustedPriceState;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerEntry;
import com.ghostchu.quickshop.addon.exchange.ledger.LedgerJournal;
import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.marketdata.Candle;
import com.ghostchu.quickshop.addon.exchange.operations.AuditRecord;
import com.ghostchu.quickshop.addon.exchange.repository.CurrencyBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.repository.AccountLedgerEntry;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.InsufficientAssetsException;
import com.ghostchu.quickshop.addon.exchange.repository.ItemBalance;
import com.ghostchu.quickshop.addon.exchange.repository.MarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.repository.MarketFeeSchedule;
import com.ghostchu.quickshop.addon.exchange.repository.MarketTradeSample;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.ghostchu.quickshop.addon.exchange.repository.TrustedMarketSnapshot;
import com.ghostchu.quickshop.addon.exchange.transfer.IdempotencyConflictException;
import com.ghostchu.quickshop.addon.exchange.transfer.RecoveryEvidence;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class JdbcExchangeRepository
    implements ExchangeRepository, TransferRepository, MarketConfigurationPersistence {
  private final ConnectionProvider connections;
  private final SqlDialect dialect;
  private final TableNames tables;
  private final TransactionFence transactionFence;

  public JdbcExchangeRepository(
      ConnectionProvider connections, SqlDialect dialect, TableNames tables) {
    this(connections, dialect, tables, TransactionFence.NONE);
  }

  public JdbcExchangeRepository(
      ConnectionProvider connections, SqlDialect dialect, TableNames tables,
      TransactionFence transactionFence) {
    this.connections = Objects.requireNonNull(connections, "connections");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.tables = Objects.requireNonNull(tables, "tables");
    this.transactionFence = Objects.requireNonNull(transactionFence, "transactionFence");
  }

  /** Replaces the persisted schedule only when every existing version remains unchanged. */
  public void storeFeeSchedule(String marketId, MarketFeeSchedule replacement)
      throws SQLException {
    Objects.requireNonNull(marketId, "marketId");
    Objects.requireNonNull(replacement, "replacement");
    inTransaction(transaction -> {
      ((JdbcTransaction) transaction).storeFeeSchedule(marketId, replacement, false);
      return null;
    });
  }

  /** Removes a retired fee version only after no open order can still select it. */
  public void archiveFeeVersion(String marketId, long feeVersion) throws SQLException {
    Objects.requireNonNull(marketId, "marketId");
    inTransaction(transaction -> {
      ((JdbcTransaction) transaction).archiveFeeVersion(marketId, feeVersion);
      return null;
    });
  }

  @Override
  public void persist(Map<String, MarketConfigurationPersistence.State> states) {
    Objects.requireNonNull(states, "states");
    try {
      inTransaction(transaction -> {
        ((JdbcTransaction) transaction).persistMarketConfigurations(states);
        return null;
      });
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to persist market configuration reload", failure);
    }
  }

  @Override
  public Map<String, MarketConfigurationPersistence.State> load(Set<String> marketIds) {
    Objects.requireNonNull(marketIds, "marketIds");
    try {
      return inTransaction(transaction ->
          ((JdbcTransaction) transaction).loadMarketConfigurations(marketIds));
    } catch (SQLException failure) {
      throw new IllegalStateException("failed to load persisted market configuration", failure);
    }
  }

  @Override
  public TransferRecord create(TransferRecord prepared) throws SQLException {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.status() != TransferStatus.PREPARED || prepared.version() != 0) {
      throw new IllegalArgumentException("transfer must be newly prepared");
    }
    return inTransaction(transaction ->
        ((JdbcTransaction) transaction).createTransfer(prepared));
  }

  @Override
  public Optional<TransferRecord> find(UUID transferId) throws SQLException {
    Objects.requireNonNull(transferId, "transferId");
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables).findTransfer(transferId);
    }
  }

  @Override
  public Optional<TransferRecord> findByRequest(UUID accountId, UUID requestId)
      throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables)
          .findTransferByRequest(accountId, requestId);
    }
  }

  @Override
  public Optional<StoredRequestResult> findRequestResult(UUID accountId, UUID requestId)
      throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(requestId, "requestId");
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables).requestResult(accountId, requestId);
    }
  }

  @Override
  public List<ExchangeTransaction.PersistedOrder> accountOpenOrders(
      UUID accountId, int limit, int offset) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    if (limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account order page");
    }
    String columns = "order_id,request_id,market_id,account_id,side,order_type,time_in_force,"
        + "limit_price,slippage_boundary,original_quantity,remaining_quantity,status,"
        + "priority_sequence,config_version,fee_version,reserved_currency,reserved_quantity,"
        + "created_at,updated_at,version";
    try (Connection connection = connections.open();
         PreparedStatement select = connection.prepareStatement(
             "SELECT " + columns + " FROM " + tables.orders()
                 + " WHERE account_id=? AND status IN ('OPEN','PARTIALLY_FILLED')"
                 + " ORDER BY CASE status WHEN 'PARTIALLY_FILLED' THEN 0 ELSE 1 END,"
                 + " priority_sequence LIMIT ? OFFSET ?")) {
      select.setString(1, accountId.toString());
      select.setInt(2, limit);
      select.setInt(3, offset);
      try (ResultSet result = select.executeQuery()) {
        List<ExchangeTransaction.PersistedOrder> orders = new ArrayList<>();
        while (result.next()) {
          Order order = new Order(UUID.fromString(result.getString("order_id")),
              UUID.fromString(result.getString("request_id")), result.getString("market_id"),
              UUID.fromString(result.getString("account_id")),
              OrderSide.valueOf(result.getString("side")),
              OrderType.valueOf(result.getString("order_type")),
              TimeInForce.valueOf(result.getString("time_in_force")),
              nullableDecimal(result, "limit_price"), nullableDecimal(result, "slippage_boundary"),
              result.getLong("original_quantity"), result.getLong("remaining_quantity"),
              OrderStatus.valueOf(result.getString("status")), result.getLong("priority_sequence"),
              result.getLong("config_version"), result.getLong("fee_version"),
              Instant.ofEpochMilli(result.getLong("created_at")),
              Instant.ofEpochMilli(result.getLong("updated_at")));
          orders.add(new ExchangeTransaction.PersistedOrder(order,
              new BigDecimal(result.getString("reserved_currency")),
              result.getLong("reserved_quantity"), result.getLong("version")));
        }
        return List.copyOf(orders);
      }
    }
  }

  private static BigDecimal nullableDecimal(ResultSet result, String column) throws SQLException {
    String value = result.getString(column);
    return value == null ? null : new BigDecimal(value);
  }

  @Override
  public List<AccountAssetBalance> accountAssets(UUID accountId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    List<AccountAssetBalance> balances = new ArrayList<>();
    try (Connection connection = connections.open();
         PreparedStatement currencies = connection.prepareStatement(
             "SELECT currency_id,available,frozen FROM " + tables.accounts()
                 + " WHERE account_id=? AND (available<>0 OR frozen<>0)");
         PreparedStatement items = connection.prepareStatement(
             "SELECT market_id,available_quantity,frozen_quantity FROM " + tables.inventory()
                 + " WHERE account_id=? AND (available_quantity<>0 OR frozen_quantity<>0)")) {
      currencies.setString(1, accountId.toString());
      try (ResultSet result = currencies.executeQuery()) {
        while (result.next()) {
          balances.add(new AccountAssetBalance("currency", result.getString("currency_id"),
              new BigDecimal(result.getString("available")),
              new BigDecimal(result.getString("frozen"))));
        }
      }
      items.setString(1, accountId.toString());
      try (ResultSet result = items.executeQuery()) {
        while (result.next()) {
          balances.add(new AccountAssetBalance("item", result.getString("market_id"),
              BigDecimal.valueOf(result.getLong("available_quantity")),
              BigDecimal.valueOf(result.getLong("frozen_quantity"))));
        }
      }
    }
    return List.copyOf(balances);
  }

  @Override
  public List<Trade> accountTrades(UUID accountId, int limit, int offset) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    if (limit < 1 || limit > 36 || offset < 0) throw new IllegalArgumentException("invalid account trade page");
    try (Connection connection = connections.open(); PreparedStatement select = connection.prepareStatement(
        "SELECT trade_id,market_id,maker_order_id,taker_order_id,buyer_account_id,seller_account_id,"
            + "price,quantity,maker_fee,taker_fee,match_sequence,executed_at FROM " + tables.trades()
            + " WHERE buyer_account_id=? OR seller_account_id=? ORDER BY executed_at DESC,trade_id LIMIT ? OFFSET ?")) {
      select.setString(1, accountId.toString());
      select.setString(2, accountId.toString());
      select.setInt(3, limit);
      select.setInt(4, offset);
      try (ResultSet result = select.executeQuery()) {
        List<Trade> trades = new ArrayList<>();
        while (result.next()) trades.add(new Trade(UUID.fromString(result.getString("trade_id")),
            result.getString("market_id"), UUID.fromString(result.getString("maker_order_id")),
            UUID.fromString(result.getString("taker_order_id")), UUID.fromString(result.getString("buyer_account_id")),
            UUID.fromString(result.getString("seller_account_id")), new BigDecimal(result.getString("price")),
            result.getLong("quantity"), new BigDecimal(result.getString("maker_fee")),
            new BigDecimal(result.getString("taker_fee")), result.getLong("match_sequence"),
            Instant.ofEpochMilli(result.getLong("executed_at"))));
        return List.copyOf(trades);
      }
    }
  }

  @Override
  public List<TransferRecord> accountTransfers(UUID accountId, int limit, int offset)
      throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    if (limit < 1 || limit > 36 || offset < 0) throw new IllegalArgumentException("invalid account transfer page");
    try (Connection connection = connections.open(); PreparedStatement select = connection.prepareStatement(
        "SELECT transfer_id,request_id,account_id,transfer_type,asset_id,amount,status,external_marker,"
            + "failure_reason,created_at,updated_at,version FROM " + tables.transfers()
            + " WHERE account_id=? ORDER BY updated_at DESC,transfer_id LIMIT ? OFFSET ?")) {
      select.setString(1, accountId.toString());
      select.setInt(2, limit);
      select.setInt(3, offset);
      try (ResultSet result = select.executeQuery()) {
        List<TransferRecord> transfers = new ArrayList<>();
        while (result.next()) transfers.add(new TransferRecord(
            UUID.fromString(result.getString("transfer_id")), UUID.fromString(result.getString("request_id")),
            UUID.fromString(result.getString("account_id")), TransferType.valueOf(result.getString("transfer_type")),
            result.getString("asset_id"), new BigDecimal(result.getString("amount")),
            TransferStatus.valueOf(result.getString("status")), result.getString("external_marker"),
            result.getString("failure_reason"), Instant.ofEpochMilli(result.getLong("created_at")),
            Instant.ofEpochMilli(result.getLong("updated_at")), result.getLong("version")));
        return List.copyOf(transfers);
      }
    }
  }

  @Override
  public List<AccountLedgerEntry> accountLedgerEntries(
      UUID accountId, int limit, int offset) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    if (limit < 1 || limit > 36 || offset < 0) {
      throw new IllegalArgumentException("invalid account ledger page");
    }
    String accountSuffix = "liability:%:" + accountId;
    try (Connection connection = connections.open();
         PreparedStatement select = connection.prepareStatement(
             "SELECT e.entry_id,j.journal_type,j.reference_id,e.asset_id,e.amount,e.created_at"
                 + " FROM " + tables.entries() + " e JOIN " + tables.journals()
                 + " j ON j.journal_id=e.journal_id"
                 + " WHERE e.account_code LIKE ?"
                 + " ORDER BY e.created_at DESC,e.entry_id DESC LIMIT ? OFFSET ?")) {
      select.setString(1, accountSuffix);
      select.setInt(2, limit);
      select.setInt(3, offset);
      try (ResultSet result = select.executeQuery()) {
        List<AccountLedgerEntry> entries = new ArrayList<>();
        while (result.next()) {
          entries.add(new AccountLedgerEntry(UUID.fromString(result.getString("entry_id")),
              result.getString("journal_type"),
              UUID.fromString(result.getString("reference_id")), result.getString("asset_id"),
              new BigDecimal(result.getString("amount")),
              Instant.ofEpochMilli(result.getLong("created_at"))));
        }
        return List.copyOf(entries);
      }
    }
  }

  @Override
  public List<AuditRecord> auditRecords(Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    Objects.requireNonNull(fromInclusive, "fromInclusive");
    Objects.requireNonNull(toExclusive, "toExclusive");
    if (!fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("audit record range must be non-empty");
    }
    try (Connection connection = connections.open();
         PreparedStatement select = connection.prepareStatement(
             "SELECT audit_id,actor_id,action,target_id,reason,before_state,after_state,created_at"
                 + " FROM " + tables.auditRecords()
                 + " WHERE created_at>=? AND created_at<? ORDER BY created_at,audit_id")) {
      select.setLong(1, fromInclusive.toEpochMilli());
      select.setLong(2, toExclusive.toEpochMilli());
      try (ResultSet result = select.executeQuery()) {
        List<AuditRecord> records = new ArrayList<>();
        while (result.next()) {
          records.add(new AuditRecord(UUID.fromString(result.getString("audit_id")),
              UUID.fromString(result.getString("actor_id")), result.getString("action"),
              result.getString("target_id"), result.getString("reason"),
              result.getString("before_state"), result.getString("after_state"),
              Instant.ofEpochMilli(result.getLong("created_at"))));
        }
        return List.copyOf(records);
      }
    }
  }

  @Override
  public List<TransferRecord> findUnfinished(UUID accountId) throws SQLException {
    Objects.requireNonNull(accountId, "accountId");
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables).findUnfinished(accountId);
    }
  }

  @Override
  public List<TransferRecord> findAllUnfinished() throws SQLException {
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables).findUnfinished(null);
    }
  }

  @Override
  public TransferRecord transition(
      UUID transferId, long expectedVersion, TransferStatus expectedStatus,
      TransferStatus targetStatus, String reason) throws SQLException {
    Objects.requireNonNull(transferId, "transferId");
    requireLegalTransition(expectedStatus, targetStatus);
    return inTransaction(transaction -> ((JdbcTransaction) transaction).transitionTransfer(
        transferId, expectedVersion, expectedStatus, targetStatus, reason));
  }

  @Override
  public TransferRecord transitionGuarded(
      UUID transferId, long expectedVersion, TransferStatus expectedStatus,
      TransferStatus targetStatus, RecoveryEvidence evidence, String reason) throws SQLException {
    Objects.requireNonNull(transferId, "transferId");
    requireGuardedTransition(expectedStatus, targetStatus, evidence);
    return inTransaction(transaction -> ((JdbcTransaction) transaction).transitionTransfer(
        transferId, expectedVersion, expectedStatus, targetStatus, reason));
  }

  private static void requireLegalTransition(
      TransferStatus expectedStatus, TransferStatus targetStatus) {
    Objects.requireNonNull(expectedStatus, "expectedStatus");
    Objects.requireNonNull(targetStatus, "targetStatus");
    boolean legal = expectedStatus == TransferStatus.PREPARED
        && (targetStatus == TransferStatus.PROCESSING
            || targetStatus == TransferStatus.FAILED
            || targetStatus == TransferStatus.REVIEW_REQUIRED)
        || expectedStatus == TransferStatus.PROCESSING
        && (targetStatus == TransferStatus.COMPLETED
            || targetStatus == TransferStatus.FAILED
            || targetStatus == TransferStatus.REVIEW_REQUIRED);
    if (!legal) {
      throw new IllegalArgumentException("illegal transfer transition");
    }
  }

  private static void requireGuardedTransition(
      TransferStatus expectedStatus, TransferStatus targetStatus, RecoveryEvidence evidence) {
    Objects.requireNonNull(expectedStatus, "expectedStatus");
    Objects.requireNonNull(targetStatus, "targetStatus");
    if (expectedStatus != TransferStatus.PROCESSING || targetStatus != TransferStatus.PREPARED
        || evidence != RecoveryEvidence.NO_MARKED_ITEMS) {
      throw new IllegalArgumentException("illegal guarded transfer transition");
    }
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
        transactionFence.acquire(connection);
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

  @Override
  public ReconciliationReport reconcile() throws SQLException {
    return inTransaction(transaction -> ((JdbcTransaction) transaction).reconcile());
  }

  @Override
  public void upsertCandle(Candle candle) throws SQLException {
    requireCandle(candle);
    inTransaction(transaction -> {
      ((JdbcTransaction) transaction).upsertCandle(candle);
      return null;
    });
  }

  @Override
  public List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive)
      throws SQLException {
    if (marketId == null || marketId.isBlank() || fromInclusive == null || toExclusive == null
        || !fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("invalid candle range");
    }
    try (Connection connection = connections.open()) {
      return new JdbcTransaction(connection, dialect, tables)
          .loadCandles(marketId, fromInclusive, toExclusive);
    }
  }

  private static void requireCandle(Candle candle) {
    if (candle == null || candle.marketId() == null || candle.marketId().isBlank()
        || candle.bucketStart() == null || candle.open() == null || candle.high() == null
        || candle.low() == null || candle.close() == null || candle.open().signum() <= 0
        || candle.high().compareTo(candle.open()) < 0 || candle.high().compareTo(candle.close()) < 0
        || candle.low().compareTo(candle.open()) > 0 || candle.low().compareTo(candle.close()) > 0
        || candle.volume() < 0 || candle.notional() == null || candle.notional().signum() < 0) {
      throw new IllegalArgumentException("invalid candle");
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
    public TransferRecord createTransfer(TransferRecord prepared) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.transfers()
              + " (transfer_id,request_id,account_id,transfer_type,asset_id,amount,status,"
              + "external_marker,failure_reason,created_at,updated_at,version)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
        writeTransfer(insert, prepared);
        insert.executeUpdate();
        return prepared;
      } catch (SQLException failure) {
        Optional<TransferRecord> existing =
            findTransferByRequest(prepared.accountId(), prepared.requestId());
        if (existing.isEmpty()) {
          throw failure;
        }
        TransferRecord original = existing.get();
        if (original.type() != prepared.type()
            || !original.assetId().equals(prepared.assetId())
            || original.amount().compareTo(prepared.amount()) != 0) {
          throw new IdempotencyConflictException();
        }
        return original;
      }
    }

    @Override
    public Optional<TransferRecord> transfer(UUID transferId) throws SQLException {
      return findTransfer(Objects.requireNonNull(transferId, "transferId"));
    }

    @Override
    public TransferRecord completeTransfer(UUID transferId, long expectedVersion)
        throws SQLException {
      return transitionTransfer(transferId, expectedVersion, TransferStatus.PROCESSING,
          TransferStatus.COMPLETED, null);
    }

    @Override
    public TransferRecord failTransfer(UUID transferId, long expectedVersion, String reason)
        throws SQLException {
      return transitionTransfer(transferId, expectedVersion, TransferStatus.PROCESSING,
          TransferStatus.FAILED, reason);
    }

    @Override
    public TransferRecord resolveReviewedTransfer(
        UUID transferId, long expectedVersion, TransferStatus targetStatus, String reason)
        throws SQLException {
      if (targetStatus != TransferStatus.COMPLETED && targetStatus != TransferStatus.FAILED) {
        throw new IllegalArgumentException("reviewed transfer must resolve to a terminal status");
      }
      return transitionTransfer(transferId, expectedVersion, TransferStatus.REVIEW_REQUIRED,
          targetStatus, reason);
    }

    private Optional<TransferRecord> findTransfer(UUID transferId) throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          transferSelect() + " WHERE transfer_id=?")) {
        query.setString(1, transferId.toString());
        return readSingleTransfer(query);
      }
    }

    private Optional<TransferRecord> findTransferByRequest(UUID accountId, UUID requestId)
        throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          transferSelect() + " WHERE account_id=? AND request_id=?")) {
        query.setString(1, accountId.toString());
        query.setString(2, requestId.toString());
        return readSingleTransfer(query);
      }
    }

    private List<TransferRecord> findUnfinished(UUID accountId) throws SQLException {
      String accountFilter = accountId == null ? "" : " AND account_id=?";
      try (PreparedStatement query = connection.prepareStatement(
          transferSelect() + " WHERE status NOT IN ('COMPLETED','FAILED')" + accountFilter
              + " ORDER BY created_at,transfer_id")) {
        if (accountId != null) {
          query.setString(1, accountId.toString());
        }
        try (ResultSet result = query.executeQuery()) {
          ArrayList<TransferRecord> records = new ArrayList<>();
          while (result.next()) {
            records.add(readTransfer(result));
          }
          return List.copyOf(records);
        }
      }
    }

    private TransferRecord transitionTransfer(
        UUID transferId, long expectedVersion, TransferStatus expectedStatus,
        TransferStatus targetStatus, String reason) throws SQLException {
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.transfers()
              + " SET status=?,failure_reason=?,updated_at=?,version=version+1"
              + " WHERE transfer_id=? AND status=? AND version=?")) {
        update.setString(1, targetStatus.name());
        if (reason == null) {
          update.setNull(2, Types.VARCHAR);
        } else {
          update.setString(2, reason);
        }
        update.setLong(3, Instant.now().toEpochMilli());
        update.setString(4, transferId.toString());
        update.setString(5, expectedStatus.name());
        update.setLong(6, expectedVersion);
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("transfer state or version changed");
        }
      }
      return findTransfer(transferId)
          .orElseThrow(() -> new SQLException("updated transfer does not exist"));
    }

    private String transferSelect() {
      return "SELECT transfer_id,request_id,account_id,transfer_type,asset_id,amount,status,"
          + "external_marker,failure_reason,created_at,updated_at,version FROM "
          + tables.transfers();
    }

    private Optional<TransferRecord> readSingleTransfer(PreparedStatement query)
        throws SQLException {
      try (ResultSet result = query.executeQuery()) {
        return result.next() ? Optional.of(readTransfer(result)) : Optional.empty();
      }
    }

    private TransferRecord readTransfer(ResultSet result) throws SQLException {
      return new TransferRecord(
          UUID.fromString(result.getString("transfer_id")),
          UUID.fromString(result.getString("request_id")),
          UUID.fromString(result.getString("account_id")),
          TransferType.valueOf(result.getString("transfer_type")),
          result.getString("asset_id"), new BigDecimal(result.getString("amount")),
          TransferStatus.valueOf(result.getString("status")),
          result.getString("external_marker"), result.getString("failure_reason"),
          Instant.ofEpochMilli(result.getLong("created_at")),
          Instant.ofEpochMilli(result.getLong("updated_at")), result.getLong("version"));
    }

    private void writeTransfer(PreparedStatement insert, TransferRecord transfer)
        throws SQLException {
      insert.setString(1, transfer.transferId().toString());
      insert.setString(2, transfer.requestId().toString());
      insert.setString(3, transfer.accountId().toString());
      insert.setString(4, transfer.type().name());
      insert.setString(5, transfer.assetId());
      writeDecimal(insert, 6, transfer.amount());
      insert.setString(7, transfer.status().name());
      if (transfer.externalMarker() == null) {
        insert.setNull(8, Types.VARCHAR);
      } else {
        insert.setString(8, transfer.externalMarker());
      }
      if (transfer.failureReason() == null) {
        insert.setNull(9, Types.VARCHAR);
      } else {
        insert.setString(9, transfer.failureReason());
      }
      insert.setLong(10, transfer.createdAt().toEpochMilli());
      insert.setLong(11, transfer.updatedAt().toEpochMilli());
      insert.setLong(12, transfer.version());
    }

    @Override
    public CurrencyBalance currency(UUID accountId, String currencyId) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          insertIgnorePrefix() + tables.accounts()
              + " (account_id,currency_id,available,frozen,version) VALUES (?,?,?,?,0)"
              + duplicateKeyNoOp("account_id"))) {
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
    public Optional<CurrencyBalance> existingCurrency(UUID accountId, String currencyId)
        throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT available,frozen,version FROM " + tables.accounts()
              + " WHERE account_id=? AND currency_id=?" + dialect.forUpdate())) {
        select.setString(1, accountId.toString());
        select.setString(2, currencyId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            return Optional.empty();
          }
          return Optional.of(new CurrencyBalance(accountId, currencyId,
              readDecimal(result, "available"), readDecimal(result, "frozen"),
              result.getLong("version")));
        }
      }
    }

    @Override
    public ItemBalance inventory(UUID accountId, String marketId) throws SQLException {
      try (PreparedStatement insert = connection.prepareStatement(
          insertIgnorePrefix() + tables.inventory()
              + " (account_id,market_id,available_quantity,frozen_quantity,version)"
              + " VALUES (?,?,0,0,0)" + duplicateKeyNoOp("account_id"))) {
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
    public Optional<ItemBalance> existingInventory(UUID accountId, String marketId)
        throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT available_quantity,frozen_quantity,version FROM " + tables.inventory()
              + " WHERE account_id=? AND market_id=?" + dialect.forUpdate())) {
        select.setString(1, accountId.toString());
        select.setString(2, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            return Optional.empty();
          }
          return Optional.of(new ItemBalance(accountId, marketId,
              result.getLong("available_quantity"), result.getLong("frozen_quantity"),
              result.getLong("version")));
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
    public MarketFeeSchedule marketFeeSchedule(String marketId) throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT fee_schedule_payload FROM " + tables.markets()
              + " WHERE market_id=?" + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("market fee schedule does not exist: " + marketId);
          }
          return FeeSchedule.from(result.getString("fee_schedule_payload"));
        }
      }
    }

    private void storeFeeSchedule(
        String marketId, MarketFeeSchedule replacement, boolean allowCurrencyScaleChange)
        throws SQLException {
      MarketFeeSchedule current = marketFeeSchedule(marketId);
      if ((!allowCurrencyScaleChange && replacement.currencyScale() != current.currencyScale())
          || replacement.activeVersion() < current.activeVersion()
          || replacement.activeVersion() > current.activeVersion() + 1) {
        throw new IllegalArgumentException("invalid fee schedule replacement");
      }
      current.versions().forEach((version, rates) -> {
        FeeRates retained = replacement.versions().get(version);
        if (retained == null || retained.makerRate().compareTo(rates.makerRate()) != 0
            || retained.takerRate().compareTo(rates.takerRate()) != 0) {
          throw new IllegalArgumentException("existing fee versions are immutable");
        }
      });
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.markets() + " SET fee_schedule_payload=? WHERE market_id=?")) {
        update.setString(1, FeeSchedule.encode(replacement));
        update.setString(2, marketId);
        if (update.executeUpdate() != 1) {
          throw new SQLException("market fee schedule does not exist: " + marketId);
        }
      }
    }

    private void persistMarketConfigurations(
        Map<String, MarketConfigurationPersistence.State> states) throws SQLException {
      for (Map.Entry<String, MarketConfigurationPersistence.State> entry : states.entrySet()) {
        String marketId = entry.getKey();
        MarketConfigurationPersistence.State replacement = entry.getValue();
        long[] current = marketVersions(marketId);
        requireNextVersion("structural", current[0], replacement.structuralVersion());
        requireNextVersion("risk", current[1], replacement.riskVersion());
        MarketFeeSchedule feeSchedule = marketFeeSchedule(marketId);
        boolean scaleChanged = feeSchedule.currencyScale() != replacement.currencyScale();
        if (scaleChanged) {
          if (replacement.structuralVersion() != current[0] + 1) {
            throw new IllegalArgumentException(
                "currency scale change requires a structural version increment");
          }
          requireNoOpenOrders(marketId);
        }
        storeFeeSchedule(marketId, new MarketFeeSchedule(
            replacement.activeFeeVersion(), replacement.currencyScale(),
            replacement.feeVersions()), scaleChanged);
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE " + tables.markets()
                + " SET structural_version=?,risk_version=? WHERE market_id=?")) {
          update.setLong(1, replacement.structuralVersion());
          update.setLong(2, replacement.riskVersion());
          update.setString(3, marketId);
          if (update.executeUpdate() != 1) {
            throw new SQLException("market configuration does not exist: " + marketId);
          }
        }
      }
    }

    private void requireNoOpenOrders(String marketId) throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT order_id FROM " + tables.orders()
              + " WHERE market_id=? AND status IN ('OPEN','PARTIALLY_FILLED') LIMIT 1"
              + dialect.forUpdate())) {
        query.setString(1, marketId);
        try (ResultSet result = query.executeQuery()) {
          if (result.next()) {
            throw new IllegalStateException(
                "currency scale change requires no open orders");
          }
        }
      }
    }

    private Map<String, MarketConfigurationPersistence.State> loadMarketConfigurations(
        Set<String> marketIds) throws SQLException {
      Map<String, MarketConfigurationPersistence.State> persisted = new HashMap<>();
      for (String marketId : marketIds) {
        long[] versions = marketVersions(marketId);
        MarketFeeSchedule fees = marketFeeSchedule(marketId);
        persisted.put(marketId, new MarketConfigurationPersistence.State(
            versions[0], versions[1], fees.activeVersion(), fees.currencyScale(),
            fees.versions()));
      }
      return Map.copyOf(persisted);
    }

    private long[] marketVersions(String marketId) throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT structural_version,risk_version FROM " + tables.markets()
              + " WHERE market_id=?" + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("market configuration does not exist: " + marketId);
          }
          return new long[] {
              result.getLong("structural_version"), result.getLong("risk_version")};
        }
      }
    }

    private static void requireNextVersion(String kind, long current, long replacement) {
      if (replacement < current || replacement > current + 1) {
        throw new IllegalArgumentException("invalid " + kind + " version replacement");
      }
    }

    private void archiveFeeVersion(String marketId, long feeVersion) throws SQLException {
      MarketFeeSchedule current = marketFeeSchedule(marketId);
      if (feeVersion == current.activeVersion()) {
        throw new IllegalArgumentException("active fee version cannot be archived");
      }
      if (!current.versions().containsKey(feeVersion)) {
        throw new IllegalArgumentException("fee schedule version does not exist: " + feeVersion);
      }
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT order_id FROM " + tables.orders()
              + " WHERE market_id=? AND fee_version=?"
              + " AND status IN ('OPEN','PARTIALLY_FILLED') LIMIT 1" + dialect.forUpdate())) {
        query.setString(1, marketId);
        query.setLong(2, feeVersion);
        try (ResultSet result = query.executeQuery()) {
          if (result.next()) {
            throw new IllegalStateException("fee version is referenced by an open order");
          }
        }
      }
      Map<Long, FeeRates> retained = new HashMap<>(current.versions());
      retained.remove(feeVersion);
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.markets() + " SET fee_schedule_payload=? WHERE market_id=?")) {
        update.setString(1, FeeSchedule.encode(new MarketFeeSchedule(
            current.activeVersion(), current.currencyScale(), retained)));
        update.setString(2, marketId);
        if (update.executeUpdate() != 1) {
          throw new SQLException("market fee schedule does not exist: " + marketId);
        }
      }
    }

    @Override
    public long marketStructuralVersion(String marketId) throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT structural_version FROM " + tables.markets()
              + " WHERE market_id=?" + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("market configuration does not exist: " + marketId);
          }
          return result.getLong("structural_version");
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
    public MarketSnapshot marketSnapshot(MarketState state, Instant cutoff) throws SQLException {
      List<PersistedOrder> orders = openOrders(state.marketId());
      List<MarketTradeSample> trades = recentTrades(state.marketId(), cutoff);
      return new MarketSnapshot(state, orders, trades,
          maximumSequence(tables.orders(), "priority_sequence", state.marketId()),
          maximumSequence(tables.trades(), "match_sequence", state.marketId()));
    }

    @Override
    public TrustedMarketSnapshot trustedMarketSnapshot(
        String marketId, Instant budgetCutoff, Instant confidenceCutoff) throws SQLException {
      Objects.requireNonNull(marketId, "marketId");
      Objects.requireNonNull(budgetCutoff, "budgetCutoff");
      Objects.requireNonNull(confidenceCutoff, "confidenceCutoff");
      TrustedPriceState state = loadTrustedPriceState(marketId);
      Instant influenceCutoff = budgetCutoff.isBefore(confidenceCutoff)
          ? budgetCutoff : confidenceCutoff;
      List<TradeInfluence> influences = loadTradeInfluences(marketId, influenceCutoff);
      List<TrustedPriceAdjustment> adjustments = loadTrustedAdjustments(marketId, confidenceCutoff);
      return new TrustedMarketSnapshot(state, influences, adjustments);
    }

    private TrustedPriceState loadTrustedPriceState(String marketId) throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT market_id,trusted_price,guidance_price,last_evaluated_at,confidence_tier,"
              + "policy_version,last_match_sequence,state_version FROM "
              + tables.trustedMarketState() + " WHERE market_id=?" + dialect.forUpdate())) {
        query.setString(1, marketId);
        try (ResultSet result = query.executeQuery()) {
          if (!result.next()) {
            throw new SQLException("trusted market state does not exist: " + marketId);
          }
          return new TrustedPriceState(result.getString("market_id"),
              readDecimal(result, "trusted_price"), readDecimal(result, "guidance_price"),
              Instant.ofEpochMilli(result.getLong("last_evaluated_at")),
              LiquidityTier.valueOf(result.getString("confidence_tier")),
              result.getLong("policy_version"), result.getLong("last_match_sequence"),
              result.getLong("state_version"));
        }
      }
    }

    private List<TradeInfluence> loadTradeInfluences(String marketId, Instant cutoff)
        throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT trade_id,market_id,match_sequence,buyer_account_id,seller_account_id,pair_key,"
              + "trade_price,quantity,reference_before,reference_after,requested_move,"
              + "accepted_move,quantity_factor,confidence_tier,policy_version,limit_reasons,"
              + "executed_at FROM " + tables.trustedMarketInfluence()
              + " WHERE market_id=? AND executed_at>=? ORDER BY executed_at,match_sequence")) {
        query.setString(1, marketId);
        query.setLong(2, cutoff.toEpochMilli());
        try (ResultSet result = query.executeQuery()) {
          List<TradeInfluence> influences = new ArrayList<>();
          while (result.next()) {
            influences.add(new TradeInfluence(
                UUID.fromString(result.getString("trade_id")), result.getString("market_id"),
                result.getLong("match_sequence"),
                UUID.fromString(result.getString("buyer_account_id")),
                UUID.fromString(result.getString("seller_account_id")),
                result.getString("pair_key"), readDecimal(result, "trade_price"),
                result.getLong("quantity"), readDecimal(result, "reference_before"),
                readDecimal(result, "reference_after"), readDecimal(result, "requested_move"),
                readDecimal(result, "accepted_move"), readDecimal(result, "quantity_factor"),
                LiquidityTier.valueOf(result.getString("confidence_tier")),
                result.getLong("policy_version"), decodeReasons(result.getString("limit_reasons")),
                Instant.ofEpochMilli(result.getLong("executed_at"))));
          }
          return List.copyOf(influences);
        }
      }
    }

    private List<TrustedPriceAdjustment> loadTrustedAdjustments(String marketId, Instant cutoff)
        throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT adjustment_id,market_id,adjustment_type,trusted_price_before,"
              + "trusted_price_after,guidance_price_before,guidance_price_after,actor_id,reason,"
              + "policy_version,adjusted_at FROM " + tables.trustedMarketAdjustment()
              + " WHERE market_id=? AND adjusted_at>=? ORDER BY adjusted_at,adjustment_id")) {
        query.setString(1, marketId);
        query.setLong(2, cutoff.toEpochMilli());
        try (ResultSet result = query.executeQuery()) {
          List<TrustedPriceAdjustment> adjustments = new ArrayList<>();
          while (result.next()) {
            String actor = result.getString("actor_id");
            adjustments.add(new TrustedPriceAdjustment(
                UUID.fromString(result.getString("adjustment_id")), result.getString("market_id"),
                AdjustmentType.valueOf(result.getString("adjustment_type")),
                readDecimal(result, "trusted_price_before"),
                readDecimal(result, "trusted_price_after"),
                readDecimal(result, "guidance_price_before"),
                readDecimal(result, "guidance_price_after"),
                actor == null ? null : UUID.fromString(actor), result.getString("reason"),
                result.getLong("policy_version"),
                Instant.ofEpochMilli(result.getLong("adjusted_at"))));
          }
          return List.copyOf(adjustments);
        }
      }
    }

    @Override
    public void insertTradeInfluence(TradeInfluence influence) throws SQLException {
      Objects.requireNonNull(influence, "influence");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.trustedMarketInfluence()
              + " (trade_id,market_id,match_sequence,buyer_account_id,seller_account_id,pair_key,"
              + "trade_price,quantity,reference_before,reference_after,requested_move,accepted_move,"
              + "quantity_factor,confidence_tier,policy_version,limit_reasons,executed_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
        insert.setString(1, influence.tradeId().toString());
        insert.setString(2, influence.marketId());
        insert.setLong(3, influence.matchSequence());
        insert.setString(4, influence.buyerAccountId().toString());
        insert.setString(5, influence.sellerAccountId().toString());
        insert.setString(6, influence.pairKey());
        writeDecimal(insert, 7, influence.tradePrice());
        insert.setLong(8, influence.quantity());
        writeDecimal(insert, 9, influence.referenceBefore());
        writeDecimal(insert, 10, influence.referenceAfter());
        writeDecimal(insert, 11, influence.requestedMove());
        writeDecimal(insert, 12, influence.acceptedMove());
        writeDecimal(insert, 13, influence.quantityFactor());
        insert.setString(14, influence.tier().name());
        insert.setLong(15, influence.policyVersion());
        insert.setString(16, encodeReasons(influence.reasons()));
        insert.setLong(17, influence.executedAt().toEpochMilli());
        insert.executeUpdate();
      }
    }

    @Override
    public void insertTrustedAdjustment(TrustedPriceAdjustment adjustment) throws SQLException {
      Objects.requireNonNull(adjustment, "adjustment");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.trustedMarketAdjustment()
              + " (adjustment_id,market_id,adjustment_type,trusted_price_before,"
              + "trusted_price_after,guidance_price_before,guidance_price_after,actor_id,reason,"
              + "policy_version,adjusted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
        insert.setString(1, adjustment.adjustmentId().toString());
        insert.setString(2, adjustment.marketId());
        insert.setString(3, adjustment.type().name());
        writeDecimal(insert, 4, adjustment.trustedPriceBefore());
        writeDecimal(insert, 5, adjustment.trustedPriceAfter());
        writeDecimal(insert, 6, adjustment.guidancePriceBefore());
        writeDecimal(insert, 7, adjustment.guidancePriceAfter());
        if (adjustment.actorId() == null) {
          insert.setNull(8, Types.VARCHAR);
        } else {
          insert.setString(8, adjustment.actorId().toString());
        }
        insert.setString(9, adjustment.reason());
        insert.setLong(10, adjustment.policyVersion());
        insert.setLong(11, adjustment.adjustedAt().toEpochMilli());
        insert.executeUpdate();
      }
    }

    @Override
    public void insertTrustedPriceState(TrustedPriceState state) throws SQLException {
      Objects.requireNonNull(state, "state");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.trustedMarketState()
              + " (market_id,trusted_price,guidance_price,last_evaluated_at,confidence_tier,"
              + "policy_version,last_match_sequence,state_version) VALUES (?,?,?,?,?,?,?,?)")) {
        insert.setString(1, state.marketId());
        writeDecimal(insert, 2, state.trustedPrice());
        writeDecimal(insert, 3, state.guidancePrice());
        insert.setLong(4, state.lastEvaluatedAt().toEpochMilli());
        insert.setString(5, state.liquidityTier().name());
        insert.setLong(6, state.policyVersion());
        insert.setLong(7, state.lastMatchSequence());
        insert.setLong(8, state.stateVersion());
        insert.executeUpdate();
      }
    }

    @Override
    public void updateTrustedPriceState(TrustedPriceState state, long expectedVersion)
        throws SQLException {
      Objects.requireNonNull(state, "state");
      try (PreparedStatement update = connection.prepareStatement(
          "UPDATE " + tables.trustedMarketState()
              + " SET trusted_price=?,guidance_price=?,last_evaluated_at=?,confidence_tier=?,"
              + "policy_version=?,last_match_sequence=?,state_version=?"
              + " WHERE market_id=? AND state_version=?")) {
        writeDecimal(update, 1, state.trustedPrice());
        writeDecimal(update, 2, state.guidancePrice());
        update.setLong(3, state.lastEvaluatedAt().toEpochMilli());
        update.setString(4, state.liquidityTier().name());
        update.setLong(5, state.policyVersion());
        update.setLong(6, state.lastMatchSequence());
        update.setLong(7, state.stateVersion());
        update.setString(8, state.marketId());
        update.setLong(9, expectedVersion);
        if (update.executeUpdate() != 1) {
          throw new ConcurrentModificationException("trusted market state version changed");
        }
      }
    }

    private static String encodeReasons(Set<LimitReason> reasons) {
      return reasons.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<LimitReason> decodeReasons(String encoded) {
      if (encoded == null || encoded.isBlank()) {
        return Set.of();
      }
      return Arrays.stream(encoded.split(","))
          .map(LimitReason::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void visitTradeHistory(String marketId, TradeVisitor visitor) throws SQLException {
      Objects.requireNonNull(visitor, "visitor");
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT trade_id,buyer_account_id,seller_account_id,price,quantity,match_sequence,"
              + "executed_at FROM " + tables.trades()
              + " WHERE market_id=? ORDER BY match_sequence" + dialect.forUpdate())) {
        if (dialect == SqlDialect.MYSQL) {
          // Connector/J streaming mode without requiring a JDBC URL option.
          select.setFetchSize(Integer.MIN_VALUE);
        }
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          while (result.next()) {
            visitor.accept(readTradeSample(result));
          }
        }
      }
    }

    private List<MarketTradeSample> recentTrades(String marketId, Instant cutoff)
        throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT trade_id,buyer_account_id,seller_account_id,price,quantity,match_sequence,"
              + "executed_at FROM " + tables.trades()
              + " WHERE market_id=? AND executed_at>=? ORDER BY match_sequence"
              + dialect.forUpdate())) {
        select.setString(1, marketId);
        select.setLong(2, cutoff.toEpochMilli());
        try (ResultSet result = select.executeQuery()) {
          ArrayList<MarketTradeSample> trades = new ArrayList<>();
          while (result.next()) {
            trades.add(readTradeSample(result));
          }
          return List.copyOf(trades);
        }
      }
    }

    private MarketTradeSample readTradeSample(ResultSet result) throws SQLException {
      return new MarketTradeSample(UUID.fromString(result.getString("trade_id")),
          UUID.fromString(result.getString("buyer_account_id")),
          UUID.fromString(result.getString("seller_account_id")), readDecimal(result, "price"),
          result.getLong("quantity"), result.getLong("match_sequence"),
          Instant.ofEpochMilli(result.getLong("executed_at")));
    }

    private long maximumSequence(String table, String column, String marketId)
        throws SQLException {
      try (PreparedStatement select = connection.prepareStatement(
          "SELECT " + column + " FROM " + table
              + " WHERE market_id=? ORDER BY " + column + " DESC LIMIT 1"
              + dialect.forUpdate())) {
        select.setString(1, marketId);
        try (ResultSet result = select.executeQuery()) {
          return result.next() ? result.getLong(1) : 0;
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
    public void appendAudit(AuditRecord record) throws SQLException {
      Objects.requireNonNull(record, "record");
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + tables.auditRecords()
              + " (audit_id,actor_id,action,target_id,reason,before_state,after_state,created_at)"
              + " VALUES (?,?,?,?,?,?,?,?)")) {
        insert.setString(1, record.auditId().toString());
        insert.setString(2, record.actorId().toString());
        insert.setString(3, record.action());
        insert.setString(4, record.targetId());
        insert.setString(5, record.reason());
        insert.setString(6, record.beforeState());
        insert.setString(7, record.afterState());
        insert.setLong(8, record.createdAt().toEpochMilli());
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

    @Override
    public ReconciliationReport reconcile() throws SQLException {
      Map<String, BigDecimal> ledgerDifferences = nonZeroTotals(readExactTotals(
          "SELECT asset_id,amount FROM " + tables.entries(), "amount"));
      Map<String, BigDecimal> custody = readExactTotals(
          "SELECT asset_id,amount FROM " + tables.entries()
              + " WHERE account_code LIKE 'custody:%'", "amount");
      Map<String, BigDecimal> liabilities = readExactTotals(
          "SELECT currency_id AS asset_id,available,frozen FROM " + tables.accounts(),
          "available", "frozen");
      mergeTotals(liabilities, readExactTotals(
          "SELECT market_id AS asset_id,available_quantity,frozen_quantity FROM "
              + tables.inventory(), "available_quantity", "frozen_quantity"));
      return new ReconciliationReport(ledgerDifferences, custodyDifferences(custody, liabilities),
          underReservedOrderCount());
    }

    private Map<String, BigDecimal> readExactTotals(String sql, String... amountColumns)
        throws SQLException {
      HashMap<String, BigDecimal> totals = new HashMap<>();
      try (PreparedStatement query = connection.prepareStatement(sql);
           ResultSet result = query.executeQuery()) {
        while (result.next()) {
          String asset = result.getString("asset_id");
          for (String amountColumn : amountColumns) {
            totals.merge(asset, new BigDecimal(result.getString(amountColumn)), BigDecimal::add);
          }
        }
      }
      return totals;
    }

    private static Map<String, BigDecimal> nonZeroTotals(Map<String, BigDecimal> totals) {
      HashMap<String, BigDecimal> nonZero = new HashMap<>();
      totals.forEach((asset, total) -> {
        if (total.signum() != 0) {
          nonZero.put(asset, total);
        }
      });
      return nonZero;
    }

    private static void mergeTotals(Map<String, BigDecimal> destination,
                                    Map<String, BigDecimal> additions) {
      additions.forEach((asset, total) -> destination.merge(asset, total, BigDecimal::add));
    }

    private static Map<String, BigDecimal> custodyDifferences(
        Map<String, BigDecimal> custody, Map<String, BigDecimal> liabilities) {
      HashSet<String> assets = new HashSet<>(custody.keySet());
      assets.addAll(liabilities.keySet());
      HashMap<String, BigDecimal> differences = new HashMap<>();
      for (String asset : assets) {
        BigDecimal difference = custody.getOrDefault(asset, BigDecimal.ZERO)
            .add(liabilities.getOrDefault(asset, BigDecimal.ZERO));
        if (difference.signum() != 0) {
          differences.put(asset, difference);
        }
      }
      return differences;
    }

    private int underReservedOrderCount() throws SQLException {
      int underReserved = 0;
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT o.side,o.order_type,o.remaining_quantity,o.reserved_currency,"
              + "o.reserved_quantity,o.limit_price,o.fee_version,m.fee_schedule_payload FROM "
              + tables.orders() + " o JOIN " + tables.markets()
              + " m ON m.market_id=o.market_id WHERE o.status IN ('OPEN','PARTIALLY_FILLED')");
           ResultSet result = query.executeQuery()) {
        while (result.next()) {
          long remaining = result.getLong("remaining_quantity");
          if (OrderSide.SELL.name().equals(result.getString("side"))) {
            if (result.getLong("reserved_quantity") < remaining) {
              underReserved++;
            }
          } else if (OrderType.LIMIT.name().equals(result.getString("order_type"))
              && requiredBuyReservation(result.getString("limit_price"), remaining,
                  result.getString("fee_schedule_payload"), result.getLong("fee_version"))
                  .compareTo(readDecimal(result, "reserved_currency")) > 0) {
            underReserved++;
          }
        }
      }
      return underReserved;
    }

    private static BigDecimal requiredBuyReservation(
        String limitPrice, long remainingQuantity, String feeSchedulePayload, long feeVersion)
        throws SQLException {
      MarketFeeSchedule schedule = FeeSchedule.from(feeSchedulePayload);
      FeeRates fees = schedule.rates(feeVersion);
      BigDecimal notional = new BigDecimal(limitPrice).multiply(BigDecimal.valueOf(remainingQuantity));
      BigDecimal fee = notional.multiply(fees.makerRate().max(fees.takerRate()))
          .setScale(schedule.currencyScale(), RoundingMode.UP);
      return notional.add(fee);
    }

    @Override
    public void upsertCandle(Candle candle) throws SQLException {
      String sql = dialect == SqlDialect.SQLITE
          ? "INSERT INTO " + tables.candles1m()
              + " (market_id,bucket_start,open_price,high_price,low_price,close_price,volume,notional)"
              + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(market_id,bucket_start) DO UPDATE SET"
              + " open_price=excluded.open_price,high_price=excluded.high_price,"
              + "low_price=excluded.low_price,close_price=excluded.close_price,"
              + "volume=excluded.volume,notional=excluded.notional"
          : "INSERT INTO " + tables.candles1m()
              + " (market_id,bucket_start,open_price,high_price,low_price,close_price,volume,notional)"
              + " VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
              + " open_price=VALUES(open_price),high_price=VALUES(high_price),"
              + "low_price=VALUES(low_price),close_price=VALUES(close_price),"
              + "volume=VALUES(volume),notional=VALUES(notional)";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, candle.marketId());
        statement.setLong(2, candle.bucketStart().toEpochMilli());
        writeDecimal(statement, 3, candle.open());
        writeDecimal(statement, 4, candle.high());
        writeDecimal(statement, 5, candle.low());
        writeDecimal(statement, 6, candle.close());
        statement.setLong(7, candle.volume());
        writeDecimal(statement, 8, candle.notional());
        statement.executeUpdate();
      }
    }

    private List<Candle> loadCandles(String marketId, Instant fromInclusive, Instant toExclusive)
        throws SQLException {
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT market_id,bucket_start,open_price,high_price,low_price,close_price,volume,notional"
              + " FROM " + tables.candles1m()
              + " WHERE market_id=? AND bucket_start>=? AND bucket_start<?"
              + " ORDER BY bucket_start ASC")) {
        query.setString(1, marketId);
        query.setLong(2, fromInclusive.toEpochMilli());
        query.setLong(3, toExclusive.toEpochMilli());
        try (ResultSet result = query.executeQuery()) {
          List<Candle> candles = new ArrayList<>();
          while (result.next()) {
            candles.add(new Candle(result.getString("market_id"),
                Instant.ofEpochMilli(result.getLong("bucket_start")),
                readDecimal(result, "open_price"), readDecimal(result, "high_price"),
                readDecimal(result, "low_price"), readDecimal(result, "close_price"),
                result.getLong("volume"), readDecimal(result, "notional")));
          }
          return List.copyOf(candles);
        }
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
      return dialect == SqlDialect.SQLITE ? "INSERT OR IGNORE INTO " : "INSERT INTO ";
    }

    private String duplicateKeyNoOp(String primaryKeyColumn) {
      return dialect == SqlDialect.MYSQL
          ? " ON DUPLICATE KEY UPDATE " + primaryKeyColumn + "=" + primaryKeyColumn : "";
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
      return new BigDecimal(result.getString(column));
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

    private static final class FeeSchedule {
      private static MarketFeeSchedule from(String payload) throws SQLException {
        try {
          JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
          if (!json.has("versions")) {
            return new MarketFeeSchedule(1, integer(json, "currencyScale"),
                Map.of(1L, new FeeRates(decimal(json, "makerFeeRate"),
                    decimal(json, "takerFeeRate"))));
          }
          JsonObject versions = json.getAsJsonObject("versions");
          Map<Long, FeeRates> entries = new HashMap<>();
          for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
            JsonObject rates = entry.getValue().getAsJsonObject();
            entries.put(Long.parseLong(entry.getKey()), new FeeRates(
                decimal(rates, "makerFeeRate"), decimal(rates, "takerFeeRate")));
          }
          return new MarketFeeSchedule(json.get("activeVersion").getAsLong(),
              integer(json, "currencyScale"), entries);
        } catch (RuntimeException failure) {
          throw new SQLException("invalid market fee schedule", failure);
        }
      }

      private static String encode(MarketFeeSchedule schedule) {
        JsonObject json = new JsonObject();
        json.addProperty("activeVersion", schedule.activeVersion());
        json.addProperty("currencyScale", schedule.currencyScale());
        JsonObject versions = new JsonObject();
        schedule.versions().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
              JsonObject rates = new JsonObject();
              rates.addProperty("makerFeeRate", entry.getValue().makerRate().toPlainString());
              rates.addProperty("takerFeeRate", entry.getValue().takerRate().toPlainString());
              versions.add(Long.toString(entry.getKey()), rates);
            });
        json.add("versions", versions);
        return json.toString();
      }

      private static BigDecimal decimal(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
          throw new IllegalArgumentException("missing JSON field: " + field);
        }
        return new BigDecimal(value.getAsString());
      }

      private static int integer(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
          throw new IllegalArgumentException("missing JSON field: " + field);
        }
        return value.getAsInt();
      }
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
