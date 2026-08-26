# Virtual Concept Stocks

Virtual concept stocks are ledger-only securities: they have no Minecraft item, no item deposit or
withdrawal, and cannot be crafted, dropped, or moved through the exchange inventory gateway. All
balances and movements live in the exchange database (`exchange_securities`,
`exchange_security_balances`, `exchange_security_ledger`, `exchange_security_audit`).

This document describes how to configure, operate, and play with virtual concept stocks.

## What a concept stock is

A concept stock is a configured market whose `asset-type` is `VIRTUAL_SECURITY`. It has a symbol,
display name, base price, total supply, minimum unit, and a lifecycle status. Players hold balances
in the assets page, submit buy/sell orders like any other exchange market, and see the stock's
symbol, total supply, asset type, and security status in the market list/detail pages.

Unlike physical item markets:

- There is no `item:` section in the market configuration; a `security:` section is required.
- `quickshop.exchange.deposit`/`withdraw` and the GUI deposit/withdraw actions are never shown for
  security balances. There is no way to move a concept stock into or out of the player's inventory.
- The custody adapter (`SecurityAssetCustody`) never constructs an `ItemStack` and never calls the
  item transfer service. Orders settle by moving security balances between accounts inside one
  database transaction.

## Configuration

Example disabled stock market (bundled in `markets.yml`):

```yaml
  concept_alpha:
    enabled: false
    display-name: Concept Alpha
    security:
      symbol: ALPHA
      name: Alpha Holdings
      description: Pure ledger concept stock
      base-price: '10.00'
      total-supply: 1000
      minimum-unit: 1
    currency: default
    base-price: '10.00'
    min-price: '1.00'
    max-price: '100.00'
    tick-size: '0.01'
    price-scale: 2
    currency-scale: 2
    min-quantity: 1
    max-quantity: 1000
    discovery-quantity: 100
    maker-fee-rate: '0.001'
    taker-fee-rate: '0.002'
    max-account-holding: 100000
    max-frozen-currency: '10000000.00'
    max-open-orders: 100
    block-container-shops: false
```

Rules:

- A virtual security market must not define an `item:` section.
- `security.base-price`, `security.currency`, and the market `base-price`/`currency` must match.
- `security.total-supply` must be a positive multiple of `security.minimum-unit`.
- `security.symbol` must be uppercase letters, digits, and underscores (up to 16 characters).
- `enabled: false` creates the market in `CLOSED` state and the security definition in `CLOSED`
  state. It is safe to ship disabled examples; nothing is tradable until an operator enables the
  market and changes the security status.

## Lifecycle and admin commands

Mutating stock operations require `quickshop.exchange.admin.stock`. Each operation is audited with
an idempotent request id.

```text
/qse admin stock create <symbol> <name> <currency> <basePrice> <totalSupply> [minimumUnit] [description...]
/qse admin stock issue <marketId> <playerUUID> <quantity> <reason...>
/qse admin stock pause <marketId> <reason...>
/qse admin stock resume <marketId> <reason...>
/qse admin stock close <marketId> <recoveryAccountUUID> <reason...>
```

Lifecycle status values: `OPEN`, `PAUSED`, `HALTED`, `CLOSED`.

- `OPEN`: new orders are accepted.
- `PAUSED`: issuance is still possible, but order entry is stopped because the matching market
  state is paused in the same transaction. The security status and the market status stay in
  sync automatically.
- `HALTED`: reserved for circuit-breaker style halts (not yet wired to automatic market halts).
- `CLOSED`: no new issuance; `close` requires zero open orders and moves every outstanding balance
  to the recovery account, then marks the definition closed.

### Recommended operating sequence

1. Configure the stock market with `enabled: false`.
2. Start the server once so the market and security rows are created.
3. Set the market to `enabled: true` and restart, or use the market pause/resume admin commands.
4. Resume the security (`/qse admin stock resume <marketId> ...`) only after the market state is
   `OPEN`. Resuming the security also opens the market state if it was paused by `stock pause`;
   a market halted for another reason (e.g. reconciliation) stays halted.
5. Issue initial supply with `/qse admin stock issue`.
6. Confirm the assets page shows the security balance and the market detail page shows
   `Asset: VIRTUAL_SECURITY`, `Symbol: <symbol>`, `Total supply: <total>`.

To close a stock, first cancel or let all open orders finish, then:
`/qse admin stock close <marketId> <recoveryAccount> <reason>`.

## Player experience

- `/qse stocks` opens the market list (all virtual and physical markets).
- `/qse stock <symbol>` or clicking the market in the list opens the market detail page.
- The assets page (`/qse assets`) shows each security as a paper icon with the explicit text
  "Virtual security (ledger-only, cannot deposit or withdraw)", plus symbol, available, and frozen
  quantities. Security rows have no left/right deposit/withdraw actions.
- Market list/detail rows display `Asset: VIRTUAL_SECURITY`, `Symbol`, `Total supply`, and
  `Security status` (read live from the security definition) in addition to the normal
  price/volume/status lore.
- `/qse stock <symbol>` resolves the symbol to its market id (case-insensitive) and opens that
  market's detail page; an unknown symbol is rejected instead of opening a broken page.
- When the market status is not `OPEN`, order-entry buttons remain visible but clicking them
  returns "This market currently accepts queries and cancellations only." The same guard applies
  to paused/closed virtual markets.

## Reconciliation and recovery

Reconciliation includes virtual securities: the issued supply is treated as custody, and player
security balances (available + frozen) are treated as liabilities. A tampered or lost security
balance therefore surfaces as a `custodyDifferences` entry for that market and triggers the same
automatic pause/alert protection as any other exchange asset.

Startup fail-closed checks verify that every configured market exists in the database, that the
database asset type matches the configuration, and that every virtual market has a security
definition. A mismatch stops startup instead of silently misbehaving.

Security balance and ledger operations are append-only and idempotent. Issue, freeze, release,
consume, and recovery write both the balance table and an immutable security-ledger row in one
transaction, so restart replay cannot double-count.

## Permissions summary

- Player: `quickshop.exchange.use`, `quickshop.exchange.order.market`, `quickshop.exchange.order.limit`
- Stock administration: `quickshop.exchange.admin.stock`
- Reconciliation/audit: `quickshop.exchange.admin.audit`
- Market pause/resume: `quickshop.exchange.admin.market`
