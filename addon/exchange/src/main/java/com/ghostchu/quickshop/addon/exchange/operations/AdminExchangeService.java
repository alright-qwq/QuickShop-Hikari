package com.ghostchu.quickshop.addon.exchange.operations;

import com.ghostchu.quickshop.addon.exchange.core.model.MarketStatus;
import com.ghostchu.quickshop.addon.exchange.ledger.ReconciliationReport;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeRepository;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction;
import com.ghostchu.quickshop.addon.exchange.repository.ExchangeTransaction.MarketState;
import com.ghostchu.quickshop.addon.exchange.repository.StoredRequestResult;
import com.ghostchu.quickshop.addon.exchange.service.OrderReceipt;
import com.ghostchu.quickshop.addon.exchange.service.PersistentOrderService;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferJournals;
import com.ghostchu.quickshop.addon.exchange.transfer.TransferRepository;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferStatus;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Coordinates audited administration through the market services that own live order books. */
public final class AdminExchangeService {
  private static final String PAUSE_MARKET_OPERATION = "PAUSE_MARKET";
  private static final String RESUME_MARKET_OPERATION = "RESUME_MARKET";
  private static final String RESOLVE_TRANSFER_REVIEW_OPERATION = "RESOLVE_TRANSFER_REVIEW";
  private static final String RECONCILE_OPERATION = "RECONCILE";
  private static final String RECONCILIATION_AUTO_PAUSE = "RECONCILIATION_AUTO_PAUSE";
  private static final String RECONCILIATION_DIFFERENCE = "RECONCILIATION_DIFFERENCE";

  private final Map<String, PersistentOrderService> markets;
  private final ExchangeRepository repository;
  private final AuditExporter auditExporter;
  private final Path auditDirectory;

  public AdminExchangeService(Map<String, PersistentOrderService> markets) {
    this(markets, null, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository) {
    this(markets, repository, null, null);
  }

  public AdminExchangeService(
      Map<String, PersistentOrderService> markets, ExchangeRepository repository,
      AuditExporter auditExporter, Path auditDirectory) {
    this.markets = Map.copyOf(Objects.requireNonNull(markets, "markets"));
    this.repository = repository;
    this.auditExporter = auditExporter;
    this.auditDirectory = auditDirectory;
  }

  public OrderReceipt forceCancel(UUID actorId, UUID requestId, String marketId, UUID orderId,
                                  String reason) throws SQLException {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    PersistentOrderService market = markets.get(marketId);
    if (market == null) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
    return market.forceCancel(actorId, requestId, orderId, reason);
  }

  public ReconciliationReport reconcile() throws SQLException {
    return requireRepository().reconcile();
  }

  public ReconciliationReport reconcile(UUID actorId, UUID requestId) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    return requireRepository().inTransaction(tx -> {
      StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
      if (stored != null) {
        if (!RECONCILE_OPERATION.equals(stored.operation())) {
          throw new IllegalStateException("request id belongs to another operation");
        }
        return reconciliationReport(stored.payload());
      }
      ReconciliationReport report = tx.reconcile();
      protectAffectedMarkets(tx, actorId, report);
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, RECONCILE_OPERATION, reconciliationStoredPayload(report)));
      return report;
    });
  }

  public Path exportAudit(Instant fromInclusive, Instant toExclusive)
      throws SQLException, IOException {
    ExchangeRepository store = requireRepository();
    AuditExporter exporter = Objects.requireNonNull(
        auditExporter, "audit exporter is required for audit export");
    Path directory = Objects.requireNonNull(
        auditDirectory, "audit directory is required for audit export");
    return exporter.export(
        directory, store.auditRecords(fromInclusive, toExclusive), fromInclusive, toExclusive);
  }

  public List<TransferRecord> pendingTransferReviews() throws SQLException {
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository transfers)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }
    return transfers.findAllUnfinished().stream()
        .filter(transfer -> transfer.status() == TransferStatus.REVIEW_REQUIRED)
        .toList();
  }

  public TransferRecord transferReview(UUID transferId) throws SQLException {
    Objects.requireNonNull(transferId, "transferId");
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository transfers)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }
    TransferRecord transfer = transfers.find(transferId)
        .orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
    if (transfer.status() != TransferStatus.REVIEW_REQUIRED) {
      throw new IllegalStateException("transfer is not awaiting review: " + transfer.status());
    }
    return transfer;
  }

  /**
   * Applies only the internal settlement implied by an administrator's external evidence.
   * This method never invokes the economy or inventory gateways.
   */
  public TransferRecord resolveReview(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision, String evidence)
      throws SQLException {
    return resolveReview(actorId, requestId, transferId, decision, evidence, false);
  }

  TransferRecord resolvedReviewRequest(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(decision, "decision");
    ExchangeRepository store = requireRepository();
    return store.inTransaction(tx -> {
      StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
      return stored == null ? null : resolvedReviewResult(tx, stored, transferId, decision);
    });
  }

  TransferRecord resolveVerifiedItemWithdrawalFailure(
      UUID actorId, UUID requestId, UUID transferId, long markedQuantity, String operatorEvidence)
      throws SQLException {
    if (markedQuantity != 0) {
      throw new IllegalStateException("marked item delivery still exists");
    }
    String evidence = "machine-marker-observation:transfer=" + transferId
        + ";marked=0;operator=" + normalizeReviewEvidence(operatorEvidence);
    return resolveReview(actorId, requestId, transferId,
        ReviewDecision.CONFIRM_EXTERNAL_FAILURE, evidence, true);
  }

  TransferRecord resolveVerifiedItemMarkerCleanup(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision,
      long markedBefore, long markedAfter, String operatorEvidence) throws SQLException {
    if (markedAfter != 0) {
      throw new IllegalStateException("marked items remain after cleanup");
    }
    String evidence = "machine-marker-cleanup:transfer=" + transferId
        + ";before=" + markedBefore + ";after=0;operator="
        + normalizeReviewEvidence(operatorEvidence);
    return resolveReview(actorId, requestId, transferId, decision, evidence, true);
  }

  private TransferRecord resolveReview(
      UUID actorId, UUID requestId, UUID transferId, ReviewDecision decision, String evidence,
      boolean machineVerifiedItemReview) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(decision, "decision");
    String normalizedEvidence = normalizeReviewEvidence(evidence);
    ExchangeRepository store = requireRepository();
    if (!(store instanceof TransferRepository)) {
      throw new IllegalStateException("repository does not support transfer reviews");
    }

    Instant resolvedAt = Instant.now();
    String operationPayload = reviewPayload(transferId, decision);
    return store.inTransaction(tx -> {
      StoredRequestResult duplicate = tx.requestResult(actorId, requestId).orElse(null);
      if (duplicate != null) {
        return resolvedReviewResult(tx, duplicate, transferId, decision);
      }
      TransferRecord review = tx.transfer(transferId)
          .orElseThrow(() -> new IllegalArgumentException("unknown transfer: " + transferId));
      requireReviewDecision(review, decision, machineVerifiedItemReview);
      applyReviewSettlement(tx, review, decision, resolvedAt);
      TransferStatus target = decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
          ? TransferStatus.COMPLETED : TransferStatus.FAILED;
      TransferRecord resolved = tx.resolveReviewedTransfer(
          review.transferId(), review.version(), target, normalizedEvidence);
      tx.appendAudit(new AuditRecord(
          UUID.randomUUID(), actorId, RESOLVE_TRANSFER_REVIEW_OPERATION,
          review.transferId().toString(), normalizedEvidence,
          transferState(review), transferState(resolved), resolvedAt));
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, RESOLVE_TRANSFER_REVIEW_OPERATION, operationPayload));
      return resolved;
    });
  }

  public void pauseMarket(UUID actorId, UUID requestId, String marketId, String reason)
      throws SQLException {
    changeMarketStatus(actorId, requestId, marketId, reason,
        PAUSE_MARKET_OPERATION, MarketStatus.PAUSED);
  }

  public void resumeMarket(UUID actorId, UUID requestId, String marketId, String reason)
      throws SQLException {
    changeMarketStatus(actorId, requestId, marketId, reason,
        RESUME_MARKET_OPERATION, MarketStatus.OPEN);
  }

  private void changeMarketStatus(
      UUID actorId, UUID requestId, String marketId, String reason,
      String operation, MarketStatus target) throws SQLException {
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(requestId, "requestId");
    requireMarket(marketId);
    String normalizedReason = normalizeReason(reason);
    ExchangeRepository store = requireRepository();
    store.inTransaction(tx -> {
      StoredRequestResult stored = tx.requestResult(actorId, requestId).orElse(null);
      if (stored != null) {
        if (!operation.equals(stored.operation())) {
          throw new IllegalStateException("request id belongs to another operation");
        }
        return null;
      }
      MarketState before = tx.marketState(marketId);
      requireTransition(before.status(), target);
      MarketState after = new MarketState(
          before.marketId(), target, before.prioritySequence(), before.matchSequence(),
          before.referencePrice(), before.lastPrice(),
          target == MarketStatus.OPEN ? null : before.haltedUntil(),
          before.discoveryQuantity(), before.circuitBreakerLevel(), before.version() + 1);
      tx.updateMarketState(after, before.version());
      Instant changedAt = Instant.now();
      tx.appendAudit(new AuditRecord(
          UUID.randomUUID(), actorId, operation, marketId, normalizedReason,
          "status=" + before.status(), "status=" + after.status(), changedAt));
      tx.putRequestResult(new StoredRequestResult(
          actorId, requestId, operation, "status=" + after.status()));
      return null;
    });
  }

  private void protectAffectedMarkets(
      ExchangeTransaction tx, UUID actorId, ReconciliationReport report) throws SQLException {
    if (report.balanced()) {
      return;
    }
    Instant detectedAt = Instant.now();
    String difference = reconciliationPayload(report);
    for (String marketId : affectedMarkets(report)) {
      MarketState before = tx.marketState(marketId);
      if (before.status() == MarketStatus.OPEN || before.status() == MarketStatus.HALTED) {
        MarketState after = new MarketState(
            before.marketId(), MarketStatus.PAUSED, before.prioritySequence(),
            before.matchSequence(), before.referencePrice(), before.lastPrice(),
            before.haltedUntil(), before.discoveryQuantity(), before.circuitBreakerLevel(),
            before.version() + 1);
        tx.updateMarketState(after, before.version());
        tx.appendAudit(new AuditRecord(
            UUID.randomUUID(), actorId, RECONCILIATION_AUTO_PAUSE, marketId, difference,
            "status=" + before.status(), "status=" + after.status(), detectedAt));
      }
      tx.insertHighAlert(
          UUID.randomUUID(), marketId, RECONCILIATION_DIFFERENCE, difference, detectedAt);
    }
  }

  private List<String> affectedMarkets(ReconciliationReport report) {
    if (report.underReservedOrders() > 0) {
      return markets.keySet().stream().sorted().toList();
    }
    java.util.Set<String> assets = new java.util.HashSet<>(report.ledgerDifferences().keySet());
    assets.addAll(report.custodyDifferences().keySet());
    List<String> affected = markets.entrySet().stream()
        .filter(entry -> assets.contains(entry.getKey())
            || assets.contains(entry.getValue().marketRules().currencyId()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
    return affected.isEmpty() ? markets.keySet().stream().sorted().toList() : affected;
  }

  private static String reconciliationPayload(ReconciliationReport report) {
    return "ledger=" + report.ledgerDifferences()
        + ";custody=" + report.custodyDifferences()
        + ";underReservedOrders=" + report.underReservedOrders();
  }

  private static String reconciliationStoredPayload(ReconciliationReport report) {
    return "v1|" + report.underReservedOrders()
        + "|" + differencePayload(report.ledgerDifferences())
        + "|" + differencePayload(report.custodyDifferences());
  }

  private static String differencePayload(Map<String, BigDecimal> differences) {
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return differences.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> encoder.encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8))
            + ":" + entry.getValue().toPlainString())
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static ReconciliationReport reconciliationReport(String payload) {
    if (payload == null || !payload.startsWith("v1|")) {
      throw new IllegalStateException("stored reconciliation result is invalid");
    }
    String[] fields = payload.split("\\|", -1);
    if (fields.length != 4) {
      throw new IllegalStateException("stored reconciliation result is invalid");
    }
    try {
      return new ReconciliationReport(
          parseDifferences(fields[2]),
          parseDifferences(fields[3]),
          Integer.parseInt(fields[1]));
    } catch (IllegalArgumentException failure) {
      throw new IllegalStateException("stored reconciliation result is invalid", failure);
    }
  }

  private static Map<String, BigDecimal> parseDifferences(String payload) {
    Map<String, BigDecimal> differences = new LinkedHashMap<>();
    if (payload.isEmpty()) {
      return differences;
    }
    Base64.Decoder decoder = Base64.getUrlDecoder();
    for (String entry : payload.split(",", -1)) {
      int separator = entry.indexOf(':');
      if (separator <= 0 || separator == entry.length() - 1) {
        throw new IllegalArgumentException("invalid reconciliation difference");
      }
      String assetId = new String(
          decoder.decode(entry.substring(0, separator)), StandardCharsets.UTF_8);
      BigDecimal difference = new BigDecimal(entry.substring(separator + 1));
      if (assetId.isBlank() || differences.putIfAbsent(assetId, difference) != null) {
        throw new IllegalArgumentException("invalid reconciliation asset");
      }
    }
    return differences;
  }

  private static void applyReviewSettlement(
      ExchangeTransaction tx, TransferRecord transfer, ReviewDecision decision, Instant at)
      throws SQLException {
    boolean success = decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS;
    switch (transfer.type()) {
      case MONEY_DEPOSIT -> {
        if (success) {
          tx.creditAvailableCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.moneyDeposit(transfer, at));
        }
      }
      case MONEY_WITHDRAWAL -> {
        if (success) {
          tx.consumeFrozenCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.moneyWithdrawal(transfer, at));
        } else {
          tx.releaseCurrency(transfer.accountId(), transfer.assetId(), transfer.amount());
          tx.appendJournal(TransferJournals.releaseMoneyWithdrawal(transfer, at));
        }
      }
      case ITEM_DEPOSIT -> {
        if (success) {
          tx.creditAvailableItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.itemDeposit(transfer, at));
        }
      }
      case ITEM_WITHDRAWAL -> {
        if (success) {
          tx.consumeFrozenItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.itemWithdrawal(transfer, at));
        } else {
          tx.releaseItems(
              transfer.accountId(), transfer.assetId(), transfer.amount().longValueExact());
          tx.appendJournal(TransferJournals.releaseItemWithdrawal(transfer, at));
        }
      }
    }
  }

  private static void requireReviewDecision(
      TransferRecord transfer, ReviewDecision decision,
      boolean machineVerifiedItemReview) {
    if (transfer.status() != TransferStatus.REVIEW_REQUIRED) {
      throw new IllegalStateException("transfer is not awaiting review: " + transfer.status());
    }
    if (transfer.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        && "inventory deposit marking result unknown".equals(transfer.failureReason())) {
      throw new IllegalStateException(
          "item deposit success requires evidence that the marked items were removed");
    }
    if (transfer.type() == TransferType.ITEM_DEPOSIT
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE
        && !machineVerifiedItemReview) {
      throw new IllegalStateException(
          "item deposit failure requires marker cleanup before terminal resolution");
    }
    if (transfer.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_FAILURE
        && !machineVerifiedItemReview) {
      throw new IllegalStateException(
          "item withdrawal failure requires machine marker verification before releasing custody");
    }
    if (transfer.type() == TransferType.ITEM_WITHDRAWAL
        && decision == ReviewDecision.CONFIRM_EXTERNAL_SUCCESS
        && !machineVerifiedItemReview) {
      throw new IllegalStateException(
          "item withdrawal success requires marker cleanup before terminal resolution");
    }
  }

  private static TransferRecord resolvedReviewResult(
      ExchangeTransaction tx, StoredRequestResult stored, UUID transferId,
      ReviewDecision decision) throws SQLException {
    if (!RESOLVE_TRANSFER_REVIEW_OPERATION.equals(stored.operation())
        || !reviewPayload(transferId, decision).equals(stored.payload())) {
      throw new IllegalStateException("request id belongs to another operation");
    }
    return tx.transfer(transferId)
        .orElseThrow(() -> new IllegalStateException("resolved transfer does not exist"));
  }

  private static String reviewPayload(UUID transferId, ReviewDecision decision) {
    return "transfer=" + transferId + ";decision=" + decision;
  }

  private static String transferState(TransferRecord transfer) {
    return "type=" + transfer.type() + ";status=" + transfer.status()
        + ";asset=" + transfer.assetId() + ";amount=" + transfer.amount().toPlainString()
        + ";version=" + transfer.version();
  }

  private static String normalizeReviewEvidence(String evidence) {
    if (evidence == null || evidence.trim().length() < 16) {
      throw new IllegalArgumentException(
          "review evidence must contain at least 16 characters");
    }
    return evidence.trim();
  }

  private ExchangeRepository requireRepository() {
    return Objects.requireNonNull(
        repository, "repository is required for exchange administration");
  }

  private void requireMarket(String marketId) {
    if (marketId == null || marketId.isBlank()) {
      throw new IllegalArgumentException("market id is required");
    }
    if (!markets.containsKey(marketId)) {
      throw new IllegalArgumentException("unknown market: " + marketId);
    }
  }

  private static void requireTransition(MarketStatus before, MarketStatus target) {
    if (target == MarketStatus.PAUSED) {
      if (before != MarketStatus.OPEN && before != MarketStatus.HALTED) {
        throw new IllegalStateException("cannot pause market from " + before);
      }
      return;
    }
    if (target == MarketStatus.OPEN && before != MarketStatus.PAUSED) {
      throw new IllegalStateException("cannot resume market from " + before);
    }
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.trim().length() < 8) {
      throw new IllegalArgumentException(
          "administrator reason must contain at least 8 characters");
    }
    return reason.trim();
  }

  /** Locates an active order across configured markets without exposing market selection to staff. */
  public OrderReceipt forceCancel(UUID actorId, UUID requestId, UUID orderId, String reason)
      throws SQLException {
    Objects.requireNonNull(orderId, "orderId");
    IllegalArgumentException missing = null;
    for (PersistentOrderService market : markets.values()) {
      try {
        return market.forceCancel(actorId, requestId, orderId, reason);
      } catch (IllegalArgumentException failure) {
        if (!failure.getMessage().startsWith("order is not open:")) {
          throw failure;
        }
        missing = failure;
      }
    }
    throw missing == null ? new IllegalArgumentException("order is not open: " + orderId) : missing;
  }
}
