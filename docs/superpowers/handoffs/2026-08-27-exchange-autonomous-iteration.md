# Exchange Autonomous Iteration Handoff (2026-08-27)

## Workspace

- Worktree: `/Users/ztrnb/QuickShop-Hikari/.worktrees/exchange-order-book`
- Branch: `codex/exchange-order-book`
- Current HEAD: `ee847348f`
- State: clean except untracked `docs/superpowers/plans/2026-08-26-virtual-concept-stock.md`
  (intentional plan document; do not commit unless the next AI decides the plan is stale).
- The user is asleep and has granted full autonomy; push failures are transient (503) and should
  be retried with `git -c http.version=HTTP/1.1 push origin codex/exchange-order-book` — never
  change the remote URL.
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Verify: `mvn -pl addon/exchange -am test -q` then `git diff --check`; last full run 402/402 green.

## What shipped since the last handoff (after 2026-07-28)

Virtual concept stocks (`asset-type: VIRTUAL_SECURITY`) are fully playable:

- Pure-ledger securities with symbol resolution (`/qse stock <symbol>`), lifecycle admin
  (`create/issue/pause/resume/close`), and audited balance/ledger persistence.
- Admin stock transfer (`/qse admin stock transfer <market|symbol> <from> <to> <qty> <reason>`)
  with immutable double-sided ledger entries and idempotent request ids.
- Trade settlement now writes immutable security-ledger rows (seller negative, buyer positive),
  and `securityLedger(marketId, null)` correctly returns the full market ledger instead of only
  rows with `owner_id IS NULL`.
- Market list: 24h change, issued/total supply, asset filter, sort control, overview (breadth,
  volume, notional, most active, top gainer/loser).
- Market detail: switchable candle timeframes (1m/15m/1h/4h), 24h high/low, volatility, issued
  supply, 24h trade summary (counts + aggressive buy/sell), six recent trades with direction.
- Order confirm: market orders now show the current executable quote (best ask for buys / best
  bid for sells) as a reference next to the absolute protection boundary.
- History: buy/sell direction (green/red), trade total fees.
- My orders / history: page indicators and empty states.
- Market trade history: a dedicated paginated page (27 rows/page) with live market
  header (last price + best bid/ask), buy/sell direction panes, and previous/next
  navigation preserving the market id.
- 24h trade summary counts only directional trades, keeping the trade count
  consistent with the aggressive buy/sell breakdown.
- Market list NOTIONAL sort now compares 24h notional (was volume), and the
  list lore shows 24h turnover.
- Open orders show the current last price next to each order.
- Recent and history trades show notional value (price x quantity).

Full exchange test suite: 403 tests, 0 failures.

## Architecture notes

- `SecurityService` performs audited, idempotent lifecycle mutations inside one repository
  transaction; balances move only through `exchange_security_ledger`.
- `SecurityAssetCustody` implements the `AssetCustody` contract; `recordsLedgerEntries()` is a
  new capability flag that tells `PersistentOrderService.appendTradeJournals` to also append
  security-ledger rows for matched virtual trades.
- UI snapshots are immutable records; all DB reads happen on the maintenance executor
  (`ExchangeViewService`), never on the player thread. The recent `marketQuote` async load on
  the confirm page uses `CompletableFuture.supplyAsync` (ForkJoinPool) as a pragmatic exception —
  acceptable for a small read, but a future improvement could route it through the same executor.

## Next iteration candidates (user-granted autonomy)

1. Route the confirm-page quote load through the view service executor instead of ForkJoinPool.
2. Market detail: add a "recent trades" page with pagination (currently only six rows shown),
   or link the detail page to account history filtered by market.
3. Physical item market parity: verify `ItemAssetCustody` and `SecurityAssetCustody` behaviors
   stay consistent for deposits/withdrawals, minimum units, and reconciliation.
4. Reconsider maker/taker fee display in history: currently the total trade fees shown are both
   parties' fees; showing "my fee" requires joining orders to determine whether the player was
   maker or taker.
5. Add end-to-end integration tests for virtual stocks (issue → trade → close) if not present.
6. UI polish: depth/candle icons could include tooltips with more levels; consider a compact
   "recent trades" ticker on the market list header.
7. Metrics/abnormal-trading detection remain non-MVP gaps from Phase 4 (metrics snapshots,
   abnormal trading detection, load IT).

## Release position

Autonomous UI/mechanism iteration continues on `codex/exchange-order-book`; no release artifact
needs to be produced for this handoff. Before any production rollout still follow
`docs/exchange-operations.md`, including real SQLite + MySQL end-to-end cycles and reconciliation
verification.
