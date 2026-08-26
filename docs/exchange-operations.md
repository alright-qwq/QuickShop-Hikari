# QuickShop Exchange Operations

## Rollout

1. Back up the QuickShop database and `plugins/qssuite-exchange` before installing a new build.
2. Start once with `enabled: false`. Confirm that `config.yml` and `markets.yml` exist; this mode
   does not construct the exchange runtime or accept orders.
3. On a test server, enable one currency and a small set of common materials. Verify deposits,
   withdrawals, a matched order, a restart, and a cancellation on both Paper and Folia.
4. On production, enable the player whitelist and conservative holding, frozen-currency, and
   open-order limits. Observe a complete economic cycle before increasing limits.
5. Reconcile custody daily. Investigate `REVIEW_REQUIRED` transfers, circuit-breaker halts,
   writer-lock failures, SQL latency, and any custody difference before reopening a paused market.
6. Expand the whitelist and limits per market. Enabling `block-container-shops` prevents future
   container-shop creation for that market's item; it never migrates or cancels existing shops.

## Database And Recovery

`database.mode: quickshop` requires a shared MySQL database. The addon holds a dedicated MySQL
advisory writer lock named `<dbPrefix>exchange_writer`; a second matcher must fail startup. A
disconnected lock immediately applies a local write fence. Because ownership is then untrusted, the
old instance performs no further database mutation, including attempts to mark markets `RECOVERING`.
Persistent recovery state is established only by a later startup that legally acquires writer
ownership; do not let the fenced instance retry or reclaim the lock automatically.

`database.mode: sqlite` is only for a regular file inside the addon data folder. The addon holds
an operating-system lock adjacent to that file. Do not place this database on a shared network
filesystem.

After an unclean shutdown, retain open orders. On restart the runtime first acquires the writer
lock, then runs migrations, market registration, order-book recovery, and money-transfer recovery
inside the writer fence before accepting writes. Player login item-transfer recovery is also
submitted through the runtime writer fence. Database faults or operator intervention should leave
affected markets `PAUSED` or `RECOVERING`; never edit the orders, trades, ledger, or transfer tables
by hand.

## Player Order Entry

Both `/qse` and `/quickshop exchange` open the same Exchange menu. A player must be in the rollout
whitelist and hold `quickshop.exchange.use` plus the permission for the selected operation.
Markets accept new orders only while `OPEN`.

The market detail page starts chat input with these formats:

- Limit order: `<quantity> <price>`; the order is `GTC`.
- Protected market order: `<quantity> <absolute-protection-boundary>`; the order is `IOC`.

The second market-order value is an absolute worst acceptable price, not a percentage. It is stored
unchanged through confirmation so a delayed confirmation cannot widen the player's protection.
Invalid input leaves the chat prompt active. The confirmation page rechecks player identity, rollout
whitelist membership, `quickshop.exchange.use`, and the operation-specific permission, and a request
can be claimed only once. Removing a player from rollout therefore invalidates an already-open
confirmation screen.

## Audited Administration

The supported privileged commands are:

```text
/qse admin order cancel <orderId> <reason>
/qse admin market pause <marketId> <reason>
/qse admin market resume <marketId> <reason>
/qse admin audit reconcile
/qse admin audit export <from> <to>
/qse admin transfer review list
/qse admin transfer review show <transferId>
/qse admin transfer review resolve <transferId> <success|failure> <evidence>
```

Audit-export times accept epoch seconds or ISO-8601 instants. The configured export directory must
be relative to the addon data directory. Mutating administration and reconciliation run behind the
same writer fence as player settlement.

A reconciliation difference immediately protects affected markets in the reconciliation
transaction. An item/market difference pauses that market; a currency difference pauses every
configured market using that currency. Under-reserved orders or a difference that cannot be mapped
safely pauses every configured market. Each affected market receives a HIGH
`RECONCILIATION_DIFFERENCE` alert; an `OPEN` or `HALTED` market is CAS-transitioned to `PAUSED` and
gets an append-only `RECONCILIATION_AUTO_PAUSE` audit record. Do not resume until custody, ledger,
and reservation evidence is understood and a subsequent reconciliation is balanced.

## Emergency Handling

Use the audited administration path for forced cancellation, market-state changes, reconciliation,
and transfer review. Record the external economy or inventory evidence for every
`REVIEW_REQUIRED` resolution. Do not repeat the external operation while investigating a transfer:
the durable transfer record is the source of truth. Item deposit failure and item withdrawal
success cannot be finalized while a persistent inventory marker may still require cleanup.

For an emergency shutdown, first stop accepting new exchange requests and let the GUI submitter,
login-recovery fence executor, transfer-recovery executor, and player-custody executor drain before
writer ownership is released. The final candle flush runs strictly inside the writer fence. If any
drain or the final flush fails, treat shutdown as failed and retain the writer lock. On Folia,
Exchange inventories are closed through each player's entity scheduler; if the platform rejects a
shutdown-time task, the addon does not fall back to cross-thread inventory access. Keep the database
and the addon data folder together in the backup so SQLite lock and configuration state remain
auditable.

## Manual Acceptance

On Paper and Folia, use two whitelisted test accounts to deposit funds and items, submit crossing
limit orders and protected market orders, verify maker-price execution, IOC remainder cancellation,
absolute protection boundaries and fees, restart with a partially filled order, cancel an order,
and withdraw with both available and full inventories. Verify all five player views: markets,
orders, assets, trades/transfers/ledger history, and confirmation feedback.

Folia validation must include players in different regions, login recovery, plugin disable while a
custody operation is active, and a check for region-thread ownership errors. Run the cycle once with
SQLite and once with MySQL. For MySQL, start a second addon instance and verify advisory-lock startup
failure; for SQLite, verify the adjacent file lock and reject network-share deployment.

Before broad rollout, run `/qse admin audit reconcile`, verify that an injected test difference
pauses the expected market and creates a HIGH alert, export the audit range, and confirm that no
negative balances, duplicate external operations, unresolved custody differences, or
`REVIEW_REQUIRED` transfers remain.

## Virtual Concept Stocks

See [docs/virtual-concept-stocks.md](virtual-concept-stocks.md) for configuration, lifecycle, and
player workflows.

Operational differences to remember:

- Virtual securities are ledger-only. They never construct item templates and never use the item
  deposit/withdraw path. If a log line ever mentions a virtual market in an item-transfer context,
  treat it as a bug.
- Reconciliation covers issued supply as custody and player security balances as liabilities.
  A custody difference on a virtual market pauses that market like any other asset difference.
- Startup verifies that configured asset types match the database and that virtual markets have
  their security definition. Fix configuration or database state, then restart; do not hand-edit
  the securities, security balances, or security ledger tables.
- `/qse stocks` opens the market list; `/qse stock <symbol>` opens that market's detail page.
