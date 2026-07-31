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

`database.mode: sqlite` is the default and the correct choice for a standalone Paper/Folia server,
including the normal case where QuickShop itself uses H2. Exchange stores its own ledger in
`exchange.sqlite` under the addon data folder; it does not attempt to write Exchange SQL into H2.
Existing configurations created before this default changed must replace `mode: quickshop` with
`mode: sqlite` when the detected QuickShop database product is H2. SQLite must use a regular file
inside the addon data folder. The addon holds an operating-system lock adjacent to that file; do not
place this database on a shared network filesystem.

`database.mode: quickshop` explicitly means reuse a shared **MySQL** QuickShop database; it is not a
request to support every database backend available to QuickShop. The addon holds a dedicated MySQL
advisory writer lock named `<dbPrefix>exchange_writer`; a second matcher must fail startup. A
disconnected lock immediately applies a local write fence. Because ownership is then untrusted, the
old instance performs no further database mutation, including attempts to mark markets `RECOVERING`.
Persistent recovery state is established only by a later startup that legally acquires writer
ownership; do not let the fenced instance retry or reclaim the lock automatically.

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

Item reviews use machine-observed custody evidence rather than trusting the operator's free text.
Cleanup-required resolutions are durably claimed as `REVIEW_PROCESSING` before any marker is removed.
The claim stores the original actor, request ID, decision, observed quantity, and operator evidence.
If cleanup or terminal settlement is interrupted, player-login recovery resumes that exact claim; a
missing or malformed claim fails closed. While marked custody exists, the addon blocks inventory
movement, double-click collection, dropping/pickup, item use and mutation, block placement, automated
moves, entity/armor-stand placement or removal, and world death drops. Do not bypass these events with
inventory-editing plugins during a review.

A reconciliation difference immediately protects affected markets in the reconciliation
transaction. An item/market difference pauses that market; a currency difference pauses every
configured market using that currency. Under-reserved orders or a difference that cannot be mapped
safely pauses every configured market. Each affected market receives a HIGH
`RECONCILIATION_DIFFERENCE` alert; an `OPEN` or `HALTED` market is CAS-transitioned to `PAUSED` and
gets an append-only `RECONCILIATION_AUTO_PAUSE` audit record. Do not resume until custody, ledger,
and reservation evidence is understood and a subsequent reconciliation is balanced.

## Managed Market Displays

Exchange can bind live market data to vanilla item-frame map walls and signs. All display commands
require a player, a valid look target, and `quickshop.exchange.admin.display` (OP by default):

```text
/qse admin display map create <marketId> [1x1|2x1|2x2] [kline|line] [1h|6h|24h|7d]
/qse admin display map mode <kline|line>
/qse admin display map period <1h|6h|24h|7d>
/qse admin display map refresh
/qse admin display map remove
/qse admin display sign bind <marketId>
/qse admin display sign refresh
/qse admin display sign remove
```

Map walls must use empty item frames on one vertical wall. Layout order is left-to-right and
then top-to-bottom as seen while facing the front of the frames; floor and ceiling frames are
rejected. `2x1`, `KLINE`, and `24h` are the create command defaults. A managed map can switch between
candlesticks and a line chart without recreating the frames. Chinese market colors are used: rise
red, fall green, flat gray. Sign commands require the player's crosshair to point directly at the
sign block; standing, wall, and hanging signs are supported.

Bindings are stored in `plugins/qssuite-exchange/displays.yml`. Back up and restore this file with
the addon data directory. Never edit it while the server is running. `displays.refresh-seconds`
controls the periodic refresh interval; `max-map-walls` and `max-signs` cap managed bindings. A zero
capacity disables creation of that display type.

Display reads and image calculation run in the background. On Folia, block/entity lookup enters the
stored region first and frame changes then enter the entity owner scheduler. Unloaded chunks are
not force-loaded; refresh is deferred until a later periodic pass or chunk-load recovery. Removing a
map binding clears only a map whose ID still matches the stored managed map, and never deletes the
item frame. Removing a sign binding leaves the sign block and its current text in place. Managed
frames and signs must be unbound with these commands before they can be rotated, emptied, edited, or
broken.

Display mutations and `displays.yml` saves are serialized. Plugin shutdown rejects new display
mutations and drains already accepted persistence work before the final registry save.

The professional chart layout is enabled by default. `1x1` uses a compact summary, `2x1` adds more
axis detail, and `2x2` shows the full price/time axes and volume area. The current in-memory minute
candle is merged with persisted candles so a manual refresh can show the active minute immediately.
The following switches can independently disable the professional layout, live candle, volume bars,
or latest-price line:

```yaml
displays:
  chart:
    professional-layout: true
    include-live-candle: true
    show-volume: true
    show-latest-price-line: true
```

## GUI Clock and Trading Handbook

The GUI clock is calculated only when a page opens or refreshes; it does not create a per-second task.
Configure it under `gui.clock` with `enabled`, an IANA `zone-id` such as `Asia/Shanghai`, and a Java
date-time `format`. Invalid zone or format values warn once at startup and use safe defaults.

Players included in rollout and holding `quickshop.exchange.use` can run `/qse book`. The generated
item carries the addon's versioned PDC signature; renaming an ordinary book does not authenticate it.
A signed handbook can be stored, dropped, or transferred. Main-hand right click opens page 1 of the
market GUI; off-hand and left-click interactions are ignored. Full inventories fail closed without
dropping an item. Configure `handbook.enabled`, `self-claim`, `prevent-duplicate`, and `material`.

Administrators with `quickshop.exchange.admin.handbook` (OP by default) can issue:

```text
/qse admin book give <onlinePlayer>
```

The target lookup is online-only and inventory mutation runs on the target player's entity owner
scheduler. Disabling the handbook invalidates opening and new issuance but does not delete existing
items. On shutdown, both the menu and handbook listeners are unregistered.

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
`REVIEW_REQUIRED`/`REVIEW_PROCESSING` transfers remain. Manual marker acceptance must also attempt
main-hand and off-hand interaction with an item frame or armor stand, double-click collection, death,
and hopper/container movement; each action must leave the marked stack under Exchange custody.
