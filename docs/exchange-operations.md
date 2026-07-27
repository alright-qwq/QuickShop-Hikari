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
disconnected lock fences writes and marks configured markets `RECOVERING`; do not restart a second
instance to reclaim it automatically.

`database.mode: sqlite` is only for a regular file inside the addon data folder. The addon holds
an operating-system lock adjacent to that file. Do not place this database on a shared network
filesystem.

After an unclean shutdown, retain open orders. On restart the runtime acquires the writer lock,
runs migrations, restores the books and transfer state, then accepts writes. Database faults or
operator intervention should leave affected markets `PAUSED` or `RECOVERING`; never edit the
orders, trades, ledger, or transfer tables by hand.

## Emergency Handling

Use the audited administration path for forced cancellation, market-state changes, reconciliation,
and transfer review. Record the external economy or inventory evidence for every
`REVIEW_REQUIRED` resolution. Do not repeat the external operation while investigating a transfer:
the durable transfer record is the source of truth.

For an emergency shutdown, first stop accepting new exchange requests, let the dispatcher drain,
and then stop the plugin. Candle data is flushed during orderly shutdown. Keep the database and
the addon data folder together in the backup so SQLite lock and configuration state remain
auditable.

## Manual Acceptance

On Paper and Folia, use two whitelisted test accounts to deposit funds and items, submit crossing
limit orders, verify maker-price execution and fees, restart with a partially filled order, and
withdraw with a full inventory. Folia validation must include players in different regions and a
check for region-thread ownership errors. Before broad rollout, confirm that no negative balances,
duplicate external operations, or unresolved custody differences remain.
