# Exchange Market Workbench Design

## Goal

Make the Exchange inventory GUI usable as a compact player trading workbench. Players must be able to scan the overall market, inspect real order-book liquidity and recent trades, and reach the four existing order-entry actions without leaving the selected market.

## Scope

- Add a market overview summary to the market-list page.
- Add a market dashboard snapshot containing one coherent quote, five bid levels, five ask levels, and recent one-minute candles.
- Render price/quantity depth bars and recent OHLCV bars in the market-detail inventory page.
- Add fixed navigation to market list, assets, and open orders.
- Show explicit empty-data states. No chart may imply trades or depth that do not exist.

## Non-goals

- Do not change matching, fees, custody, market risk rules, or order-entry formats.
- Do not add a browser UI, Canvas rendering, synthetic candles, price predictions, or an external chart dependency.
- Do not expose `OrderBook` or repository objects to the UI.

## Data Flow

`PersistentOrderService` exposes immutable, lock-protected depth data derived from its committed book. `MarketDataService` exposes a merged persisted/in-memory candle query. `ExchangeViewService` combines those reads on its existing maintenance executor into an immutable `MarketDashboardSnapshot`; the page receives only that snapshot.

`MarketDashboardPresenter` is pure code. It selects at most five best bid levels and five best ask levels, computes cumulative quantity and a bounded bar strength relative to the visible maximum, and turns at most nine chronological candles into display rows. This gives deterministic tests for ordering, empty states, and no-color-only meaning.

`MarketOverviewSnapshot` is built from the same `MarketRow` list. It reports market count, rising/falling counts, aggregate 24-hour volume and notional, and the most active, strongest, and weakest markets.

## Inventory Layout

The six-row market-detail page uses stable slots:

- Top row: selected market quote, spread, status, and navigation.
- Row two: five best bids on the left and five best asks on the right, with text labels for side, price, quantity, cumulative quantity, and whether a level is currently executable.
- Row three: nine recent one-minute OHLCV bars. Each bar lore contains time, open, high, low, close, and volume; direction is shown by text as well as material color.
- Rows four and five: limit buy, limit sell, protected market buy, and protected market sell at fixed, separated slots.

The market list reserves its top row for a non-clickable overview icon and actionable navigation, then lists individual markets below it. The displayed large-number fields use grouped decimal text.

## Error And Refresh Behavior

All reads remain off the entity thread and are rendered back through Folia scheduling. A failed dashboard load replaces the page with the existing data-unavailable state. Missing depth or fewer than two candles render labeled neutral placeholders rather than a misleading chart. Existing context identity checks continue to reject stale asynchronous page renders.

## Tests

Tests cover:

- candle query merges persisted and current candles chronologically;
- depth snapshots use a committed-book copy and preserve bid/ask price priority;
- presenter selection, cumulative quantity, normalization, and empty chart states;
- overview aggregation and deterministic tie handling;
- existing Exchange view tests continue to cover background-only reads.
