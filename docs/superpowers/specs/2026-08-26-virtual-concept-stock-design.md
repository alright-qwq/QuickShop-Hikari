# Virtual Concept Stock Design

## Goal

Add administrator-managed concept stocks to the exchange as pure virtual ledger assets. A stock is identified by a unique symbol, can be issued and allocated by administrators, and can be traded through the existing order book without ever becoming a Minecraft item.

## Scope and non-goals

In scope:

- Administrator creation, issuance, pause/resume, and close of a stock market.
- Fixed total supply with auditable issuance, allocation, transfer, freeze, release, and recovery records.
- Available/frozen stock balances and settlement through the existing matching, fee, price-band, halt, quote, and recovery paths.
- Stock discovery, detail, holdings, order, and cancellation views in commands and the exchange GUI.
- Backward-compatible schema migration for existing physical-item markets.

Out of scope:

- Player-created companies or securities.
- Dividends, voting, governance, buybacks, splits, dilution, yield promises, or corporate simulation.
- Backpack deposit/withdrawal, item conversion, or physical transfer of a stock.
- Short selling, margin, borrowing, or negative balances.

## Domain model

`MarketDefinition` gains an `AssetType` discriminator:

- `PHYSICAL_ITEM`: existing behavior; requires item fingerprint/template and uses `ItemTransferService`.
- `VIRTUAL_SECURITY`: no item fingerprint/template; requires stock metadata and uses the securities ledger.

Virtual-security metadata:

- `symbol`: uppercase, unique, stable market identifier.
- `name`: display name.
- `description`: administrator-supplied explanation.
- `currency`: existing exchange currency identifier.
- `basePrice`: positive initial reference price in the market currency.
- `totalSupply`: fixed positive integer in the stock's minimum unit.
- `minimumUnit`: positive integer quantity step; all issuance and orders are multiples of it.
- `issuedSupply`: current allocated amount; never exceeds `totalSupply`.
- `status`: `OPEN`, `PAUSED`, `HALTED`, or `CLOSED`.
- `recoveryAccount`: required before close; receives all unallocated/remaining stock.

The existing market key remains the primary identity. A stock symbol is unique across all markets and is persisted separately from display text.

## Persistence and migration

Add a forward-only migration after the current schema version:

1. Add nullable `asset_type` to `exchange_markets`, default existing rows to `PHYSICAL_ITEM`, then enforce non-null.
2. Relax `item_fingerprint` and `item_template` for virtual markets while retaining validation for physical markets.
3. Add `exchange_securities` with one row per virtual market: market id, symbol, name, description, currency, base price, total supply, issued supply, minimum unit, status, recovery account, created/updated timestamps, and an optimistic version.
4. Add `exchange_security_balances`: market id, owner UUID/account id, available quantity, frozen quantity, updated timestamp, and a unique `(market_id, owner_id)` key.
5. Add `exchange_security_ledger`: immutable event id, idempotency key, market id, owner, event type, signed quantity, available delta, frozen delta, reference type/id, actor, reason, created timestamp, and a unique idempotency key.
6. Add `exchange_security_audit`: immutable administrator action id, request id, market id, action, actor, payload summary, outcome, and created timestamp; request id is unique.

All writes that alter supply or balances occur in one database transaction. Existing rows and physical-market code paths remain readable by older migrations and retain their current semantics.

## Ledger invariants

- `0 <= available` and `0 <= frozen` for every balance.
- `issuedSupply` equals the sum of all positive allocation quantities minus explicit recovery quantities; it never exceeds `totalSupply`.
- For each owner, `available + frozen` equals the net quantity allocated/transferred to that owner.
- A stock transfer debits one owner and credits another atomically; total outstanding quantity is unchanged.
- Freezing moves quantity from available to frozen; releasing moves it back; consuming frozen quantity reduces outstanding quantity only when paired with the buyer's currency credit and an immutable trade record.
- Every external mutation has a unique idempotency key and an audit/ledger event. Retrying the same request returns the original result without applying deltas twice.

## Lifecycle

### Create

An administrator creates a virtual market with symbol, name, description, currency, base price, total supply, and minimum unit. Creation rejects duplicate symbols, invalid quantities/prices, and item-only fields. The market starts `OPEN` with `issuedSupply = 0`.

### Issue/allocation

An administrator issues a multiple of `minimumUnit` to a target player/account with a reason and request id. The transaction checks status (`OPEN` or `PAUSED` is allowed for allocation), available unissued supply, and account validity; increments `issuedSupply`, credits the target's available balance, and appends ledger and audit records. No item is minted or changed.

### Pause/resume/halt

`PAUSED` is an administrative state; `HALTED` is used by existing circuit-breaker/reliability logic. In either state, queries and order cancellation remain available, while new orders and matching are rejected. Resume returns to `OPEN` only after a valid administrator action. Existing halt semantics remain authoritative.

### Close/recovery

Closing requires no open orders, a valid recovery account, and an administrator reason/request id. In one transaction, all remaining outstanding stock (including balances held by players) is transferred to the recovery account, a recovery ledger event is written, the market is marked `CLOSED`, and future issue/order/transfer operations are rejected. Nothing is silently destroyed.

## Orders and settlement

Order, trade, fee, price-band, and quote models are reused. The order service selects the asset adapter from `AssetType`:

- Buy: freeze the buyer's currency as today; on execution credit the seller's currency and consume the seller's frozen stock.
- Sell: freeze the seller's available stock in `exchange_security_balances`; on execution consume that frozen quantity and credit the buyer's available stock.
- Cancel/reject/expire: release the corresponding currency or stock freeze.

An order quantity must be a positive multiple of `minimumUnit`. A virtual market never invokes item fingerprint matching, item templates, inventory reads, or `ItemTransferService`. Existing physical-market settlement is unchanged.

## Commands and permissions

Player commands:

```text
/qse stocks
/qse stock <symbol>
/qse buy <symbol> <quantity> <price>
/qse sell <symbol> <quantity> <price>
/qse cancel <order-id>
/qse assets
```

Administrator commands:

```text
/qse admin stock create <symbol> <name> <currency> <basePrice> <totalSupply> [minimumUnit] [description]
/qse admin stock issue <symbol> <player> <quantity> <reason> <request-id>
/qse admin stock pause <symbol> <reason> <request-id>
/qse admin stock resume <symbol> <reason> <request-id>
/qse admin stock close <symbol> <recovery-account> <reason> <request-id>
```

Administrative operations require the existing exchange administrator permission plus a dedicated stock-management permission where the command framework supports it. User-facing errors identify invalid symbols, state, units, balances, duplicate request ids, and open-order blockers without leaking database details.

## GUI and user experience

- Market lists include a `VIRTUAL_SECURITY` asset-type label and symbol.
- Stock detail/workbench shows name, symbol, description, currency, base price, status, total/issued/remaining supply, minimum unit, depth, recent trades, and OHLCV.
- Detail text explicitly states: “Virtual security; cannot be deposited, withdrawn, or converted to an item.”
- Assets view shows stock available and frozen holdings. It does not render item deposit/withdraw controls for stocks.
- Existing order entry and cancel flows are reused, with unit validation and state-aware disabled actions.
- During pause/halt/close, query and cancel controls remain available; order entry and matching controls are disabled with a concise reason.

## Error handling and recovery

The repository uses transactions and optimistic version checks for security rows and balances. On startup, recovery/reconciliation verifies balance non-negativity, issued supply bounds, and idempotency uniqueness; inconsistencies fail closed for the affected market and emit an actionable audit/log entry. Replaying a ledger event is prohibited; repair requires a new compensating event through an administrator-only recovery path.

## Testing strategy

Unit and repository tests cover:

- schema migration of old physical markets;
- symbol, price, supply, and minimum-unit validation;
- issuance, duplicate request id, insufficient unissued supply, and audit/ledger contents;
- available/frozen transitions and atomic stock transfers;
- buy/sell/cancel settlement for virtual markets and non-invocation of item services;
- pause/halt/close state rules and recovery-account transfer;
- command permission/error mapping;
- GUI asset rendering and absence of item deposit/withdraw controls.

Integration tests run the existing physical-market suite unchanged plus mixed-market scenarios, restart/recovery checks, and full Gradle verification.

## Acceptance criteria

The feature is complete when an administrator can create and issue a stock, two players can trade it through the existing order book, balances and freezes survive restart, all mutations are auditable and idempotent, closed markets recover all outstanding stock, and no stock operation creates, consumes, or transfers a Minecraft item. Existing physical-item market tests and behavior continue to pass.
