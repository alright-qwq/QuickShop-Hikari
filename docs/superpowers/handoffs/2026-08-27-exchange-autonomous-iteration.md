# Exchange Autonomous Iteration Handoff (2026-08-27)

## Workspace

- Worktree: `/Users/ztrnb/QuickShop-Hikari/.worktrees/exchange-order-book`
- Branch: `codex/exchange-order-book`
- Current HEAD: `63afd65ce`
- State: clean except untracked `docs/superpowers/plans/2026-08-26-virtual-concept-stock.md`
  (intentional plan document; do not commit unless the next AI decides the plan is stale).
- The user is asleep and has granted full autonomy; push failures are transient (503) and should
  be retried with `git -c http.version=HTTP/1.1 push origin codex/exchange-order-book` — never
  change the remote URL.
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- Verify: `mvn -pl addon/exchange -am test -q` then `git diff --check`; last full run 415/415 green.

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
- Limit order confirmations also show the current executable quote.
- Account history trades show notional value.
- Trade-summary lore shows 24h volume.

Full exchange test suite: 404 tests, 0 failures (includes issue-trade-close lifecycle).

Since the last update:
- Open orders now show the frozen funds (buy) or frozen quantity (sell) on each order row,
  making it clear how much of the player's balance is reserved while the order is live.

## This iteration (2026-08-27, autonomous)

- Fixed a latent clean-build failure: `addon/exchange` used TNML/TNIL classes without
  declaring them; added `TNML-CORE`/`TNIL-Core` dependencies and CodeMC/tcoded
  repositories so a fresh `mvn test` compiles instead of relying on stale incremental
  classes. The earlier `serviceWithMarketData` visibility error was package-private
  access from a `ui`-package test; the fixture method is now `public`.
- Dashboard/overview/market-list 24h notional consistently rounded to two decimals,
  with an end-to-end test (real matching -> market data -> dashboard/overview).
- Market list icons distinguish virtual securities (emerald), item markets (chest) and
  non-open markets (barrier); 24h change lines are green/red/yellow on list and detail.
- My Orders page shows a "no open orders" empty state (en/zh).
- Trade history now shows the exact fee the player paid ("my fee"): the repository
  resolves the taker account via LEFT JOIN to orders, and the view attributes maker fee
  to the resting account and taker fee to the aggressive account. Combined both-party
  fee is still shown. (408b3b8cd)
- Market list is paginated (36 per page) with previous/next/page indicator; sorting by
  change or last price is null-safe for freshly listed markets with no trades. (67d96976c)
- Market detail no longer NPEs on a no-trade market (24h change shows "-"); market trade
  history guards missing market id and null last price. (4b6dc20d8)
- Order confirm now tells the player whether a limit order will match immediately or
  rest on the book, based on the current quote (en/zh). (4b6dc20d8)

Full exchange test suite: 407 tests, 0 failures.

## Continuation (same day, autonomous)

- Accurate next-page detection for My Orders and Market Trade History: request
  PAGE_SIZE+1 rows and slice on render, so an exactly-full last page no longer shows
  a phantom next-page button. (047e93a7f)
- Order confirm shows the currency frozen on submit for buy orders (before fees);
  market list/detail show float market cap for virtual securities. (cf47a514d)
- Assets overview shows the total frozen currency next to portfolio value. (774be6db5)
- Virtual-stock settlement rollback is now covered for every settlement stage:
  security balances, security ledger and item journals all return to the exact
  pre-trade state and the market enters recovery. (73924d83f)
- New SQLite repository test proves the accountTrades orders JOIN resolves the taker
  account and attributes maker/taker fees correctly on real SQL. (711c8a817)
- Order-entry icons on virtual markets remind players the asset settles as a ledger
  balance, not a Minecraft item. (03330148c)
- Market list/detail render no-trade markets (null last price) with '-' instead of
  NPE; overview sort comparators are null-safe for change/notional. (f591a58a6)
- Player guide `docs/virtual-concept-stocks.md` synced with all of the above.

Full exchange test suite: 416 tests, 0 failures.

## Third continuation (same day)

- Asset page transfers are now paginated (12 per page, next-page probe) with a page
  indicator and previous/next navigation; assets and virtual securities render in fixed
  sections (slots 9-20 / 21-32) so transfers (33-44) can never be hidden, with "+N more"
  notices when a configured section overflows. Market ranking comparators are also
  null-safe for no-trade markets. (131e1540f)
- Cancellation confirmation now loads the open order and shows the market, side,
  remaining quantity and the exact frozen currency/quantity that will be released on
  cancel; a loading line is shown while the async read is in flight. (8fb9cc582)
- Market detail adds an "executable depth" summary (slot 7): the total bid and ask
  quantity currently executable (inside the price cage) across the whole book. (cfe231c0e)

Full exchange test suite: 421 tests, 0 failures.

## Tenth continuation (same day)

- Suspicious-trading detection is now production-wired: a maintenance task scans the last five
  minutes of trades every five minutes for high-frequency reciprocal trades between the same two
  accounts and a 10-minute cancel/place ratio, deduplicates repeats for 10 minutes, and persists
  alerts through the repository into the existing `exchange_audit_alerts` table. Detection is
  strictly read-only over accounts, balances and orders. (`d0ff4160f`)
- Operational audit status: `/qse admin audit status` prints per-market matching-latency
  percentiles and queue length, the latest alerts, and pending transfer-review count.
  `ExchangeMetrics` is wired into the runtime (trade-event inter-arrival latency as a proxy for
  matching latency) and the management service composes metrics + recent alerts + pending
  reviews. (`d0ff4160f`)
- Repository additions: `insertAuditAlert`, `recentAlerts`, `openAlerts`,
  `tradesForDetection`, `orderActivities` — all immutable reads/writes with SQLite coverage.
- UI/chart polish: every candle icon now shows change amount and change percentage; every depth
  row shows the value (price × quantity) at that level. (`5ba9d839c`)

Full exchange test suite: 430 tests, 0 failures.

## Eleventh continuation (same day)

- Alert acknowledgement: `/qse admin audit ack <alertId>` marks one alert acknowledged
  (repository `acknowledgeAlert`, admin service + router through the writer fence); audit
  status now prints each alert's id so operators can ack directly. (`bb64b21bd`)
- Trade match sequence is now visible in market detail recent trades, the paginated market
  trade history, and the player history page, so trades can be referenced across views. (`8fa5356a3`)
- Fixed a real detector bug: `orderActivities` compared non-existent status strings
  (`PLACED`/`EXPIRED`/`FILLED_CANCELLED_REMAINDER`), which silently dropped cancel events.
  It now emits a PLACE for every order created in the window and a CANCEL for every order
  cancelled in the window, with a real-SQL regression test. (`bb391036b`)
- End-to-end coverage: `tradesForDetection` feeds the reciprocal-trade detector over real
  SQLite rows; alert acknowledgement ordering is deterministic in the test. (`7c22644cc`)

Full exchange test suite: 435 tests, 0 failures.

## Thirteenth continuation (same day)

- `/qse help` prints a bilingual command overview for players; tab completion includes
  `help`. (`24e0c7d5e`)

Full exchange test suite: 436 tests, 0 failures.

## Fifteenth continuation (same day)

- Full observability integration test: real matching produces reciprocal trades, market-data
  events feed `ExchangeMetrics`, the detector scans repository trades, alerts persist and
  `AdminExchangeService.auditStatus()` reflects both metrics and alerts. (`398dd34d7`)

Full exchange test suite: 437 tests, 0 failures.

## Nineteenth continuation (same day)

- Alert acknowledgement is now audited: the actor is recorded in `exchange_audit_records`
  (`ACKNOWLEDGE_ALERT`) and repeats are idempotent (only the first write appends a record).
  (`0fbe40e4a`)
- Audit status now prints the open (unacknowledged) alert count. (`ae639b181`)
- Detection scans no longer full-scan: `orders(created_at)` and `trades(executed_at)` indexes
  cover the five-minute detection windows. (`63afd65ce`)

Full exchange test suite: 438 tests, 0 failures.

## Eighteenth continuation (same day)

- Suspicious-trading alerts are now logged to the server console at WARNING as they persist,
  so operators see them without polling audit status. (`7776e0618`)

Full exchange test suite: 437 tests, 0 failures.

## Seventeenth continuation (same day)

- Audit status now prints each alert's evidence payload, so operators can triage without
  opening the database. (`a3f861a58`)

Full exchange test suite: 437 tests, 0 failures.

## Sixteenth continuation (same day)

- Added DB indexes for hot read paths: audit alerts by (created, acknowledged), open orders by
  (account, status, updated), and trades by buyer/seller account for player history pages.
  All index creation is idempotent so existing databases pick them up on next migration.
  (`8053e8307`, `d95b82cd9`, `478ca645d`)

Full exchange test suite: 437 tests, 0 failures.

## Fourteenth continuation (same day)

- Trade-history header now shows the market's 24h change alongside last price and best
  bid/ask, matching the market detail view. (`235bf176f`)

Full exchange test suite: 436 tests, 0 failures.

## Twelfth continuation (same day)

- Market detail now shows the player's open-order count against the per-market limit
  (`N / limit`) instead of hiding it at zero; `PersistentOrderService` exposes
  `accountOrderLimits()`. (`56712f24c`)
- Market detail now estimates affordable buy quantity at the last price (available currency
  divided by worst-case price including max fee, rounded down to min quantity) and sellable
  quantity (available holding), so players can size orders without leaving the page.
  (`566e2a86b`)

Full exchange test suite: 435 tests, 0 failures.

## Ninth continuation (same day)

- Market detail now shows the player's open-order count in the selected market, loaded
  alongside balances and dashboard so players can avoid stacking orders. (a38a4ad53)
- Market list colors flat (0%) changes yellow, and My Orders shows the last price
  distance from the order price. (674f522fe)

Full exchange test suite: 421 tests, 0 failures.

## Eighth continuation (same day)

- Order confirmation now shows the market's quantity range, price range, and tick size
  alongside the existing fee/frozen estimates, so players can validate their order
  before submitting. (bba0b62e8, 3c4638a3e)

Full exchange test suite: 421 tests, 0 failures.

## Seventh continuation (same day)

- Market detail now shows the player's own balances (currency + holding/security) and the
  balance query failure is isolated so the dashboard still renders. MarketRow gained
  null guards on identity and status. (0e214566c, 0bbefd278, 64cb1a8f1, c8102580f)

Full exchange test suite: 421 tests, 0 failures.

## Sixth continuation (same day)

- Market detail now shows the player's own available and frozen balances for the selected
  market (currency for item markets, security holding for virtual stocks, and item
  holding for item markets), loaded in parallel with the dashboard so players can see
  what they can afford before ordering. (0e214566c)

Full exchange test suite: 421 tests, 0 failures.

## Fifth continuation (same day)

- Cancellation confirmation now distinguishes "loading" from "no longer cancellable"
  (filled or already cancelled) instead of showing a stuck loading line. (21b29bc7f)
- Account history ledger rows show the associated reference id, and transfer rows show
  failure reasons when present. (96b4c70b7)

Full exchange test suite: 421 tests, 0 failures.

## Fourth continuation (same day)

- Account history sections now use the same N+1 next-page probe as orders/trades, so a
  full 12-row page no longer shows a phantom "next page" button. (7a15ee146)
- Assets and My Orders pages live-refresh on market trade updates, so balances and open
  orders stay current while the menu is open. (af1f54b6c, 133edbafa)
- Confirmation pages now show the actual worst-case frozen amount (notional + max fee
  rate) matching `ReservationCalculator`, and the fee estimate uses the taker rate for
  a limit order that crosses the book and the maker rate for a resting one. (536e50a94,
  07e73ac80)
- Market list sort/filter controls are clearer (comparator icon for sort, explicit
  "current" line, and a distinct filter icon). (5fa1a0260)
- After a successful submission the player is returned to the relevant page: market
  detail for orders, My Orders after a cancel, and Assets after a transfer. (3732e8cf8)

Full exchange test suite: 421 tests, 0 failures.

## Second continuation (same day)

- Order confirm shows the applicable maker/taker fee rate, an estimated fee, and
  (for sells) the estimated net proceeds, using the market's fee schedule.
  Added `ExchangeViewService.market(id)` accessor with test coverage. (4f162cf3c)
- Fixed a real market-detail layout bug: the timeframe control (slot 16) and 24h
  trade summary (slot 15) overlapped the ask depth levels (slots 14-18), hiding
  depth rows. Moved to slots 5/6; verified no other page has slot collisions. (ce67ccc00)

Full exchange test suite: 416 tests, 0 failures.

## Architecture notes

- `SecurityService` performs audited, idempotent lifecycle mutations inside one repository
  transaction; balances move only through `exchange_security_ledger`.
- `SecurityAssetCustody` implements the `AssetCustody` contract; `recordsLedgerEntries()` is a
  new capability flag that tells `PersistentOrderService.appendTradeJournals` to also append
  security-ledger rows for matched virtual trades.
- UI snapshots are immutable records; all DB reads happen on the maintenance executor
  (`ExchangeViewService`), never on the player thread. Confirm-page quote loads route through
  `marketQuoteAsync` on that same executor.

## Next iteration candidates (user-granted autonomy)

0. Push any remaining worktree state; verify remote has no newer commits before starting.
1. Physical item market parity: verify `ItemAssetCustody` and `SecurityAssetCustody` behaviors
   stay consistent for deposits/withdrawals, minimum units, and reconciliation.
2. Reconsider maker/taker fee display in history: currently the total trade fees shown are both
   parties' fees; showing "my fee" requires joining orders to determine whether the player was
   maker or taker.
3. Add end-to-end integration tests for virtual stocks (issue → trade → close) if not present.
4. UI polish: depth/candle icons could include tooltips with more levels; consider a compact
   "recent trades" ticker on the market list header.
5. Metrics/abnormal-trading detection remain non-MVP gaps from Phase 4 (metrics snapshots,
   abnormal trading detection, load IT). Metrics/alerts are now wired; remaining gap is alert
   acknowledgement is now implemented; remaining gap is any load/integration test.
6. Confirm-page quote freshness and race handling (context token vs quote load time) could be
   tightened; the render guard already checks the menu context before drawing.

## Release position

Autonomous UI/mechanism iteration continues on `codex/exchange-order-book`; no release artifact
needs to be produced for this handoff. Before any production rollout still follow
`docs/exchange-operations.md`, including real SQLite + MySQL end-to-end cycles and reconciliation
verification.
