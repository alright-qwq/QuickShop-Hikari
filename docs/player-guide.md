# QuickShop Exchange Player Guide

The exchange is a central order book where players trade fungible assets with each other:
physical item markets (e.g. diamonds, iron) and virtual concept stocks (ledger-only securities
with symbols such as `ALPHA`). This guide explains what you can do, the ideas you need to know,
where the interesting gameplay is, and how to use every command and screen.

## What is the exchange for?

Your goal as a player is to grow a portfolio: buy assets when they are cheap, sell them when
they are expensive, and manage the currency, items, and stock holdings shown on the assets
page. The exchange is player-driven: prices move because other players buy and sell, not
because the server sets them.

The interesting gameplay comes from:

- **Price discovery.** A market's last price, 24h change, 24h high/low, and volatility tell you
  how active and how risky a market is. Low-liquidity concept stocks can move sharply when one
  large order trades.
- **The spread.** The difference between the best bid (highest buy order) and the best ask
  (lowest sell order) is what you pay to trade immediately. Buying at the ask and selling at
  the bid in quick succession loses the spread plus fees; that is the cost of impatience.
- **Making the market.** Placing a limit order slightly better than the current best price can
  earn the spread when other players trade into your order, but your order may also sit unfilled
  while the price moves against you. You earn the maker fee rate, which is lower than the taker
  fee rate.
- **Timing.** The candle chart (1m/15m/1h/4h), recent-trade direction (green aggressive buys,
  red aggressive sells), and 24h volume help you read whether buying or selling pressure is
  winning.
- **Concept stocks.** A stock with a fixed total supply becomes more scarce as supply is issued
  and held; its float market cap (issued supply × last price) shows how large it is. Lifecycle
  news (issuance, pause, close) moves expectations about what it is worth.

## Before you start

- You must be on the server's exchange whitelist (rollout). If you are not, commands answer
  "This account is not included in the current exchange rollout."
- You need the base permission `quickshop.exchange.use`, plus the specific permission for each
  operation: `quickshop.exchange.order.limit`, `quickshop.exchange.order.market`,
  `quickshop.exchange.order.cancel`, `quickshop.exchange.deposit`, and
  `quickshop.exchange.withdraw`.
- Two commands open the same menu: `/qse` and `/quickshop exchange`. `/qse help` prints a
  command overview.

## Core ideas you need to understand

### Orders are price/quantity promises

- **Limit order** (`GTC`, good-until-cancelled): you choose quantity and price. It rests on the
  book until someone trades into it or you cancel it. The confirmation page tells you whether it
  will match immediately or rest, and shows the fee and frozen amount before you submit.
- **Protected market order** (`IOC`, immediate-or-cancel): you choose quantity and an absolute
  protection boundary — the worst price you will accept. It tries to fill immediately against
  the book and cancels whatever cannot fill. The boundary is an absolute price, not a
  percentage, and it is stored unchanged through confirmation so a delayed click cannot widen
  your protection.

Chat format:

```text
limit order:  <quantity> <price>            e.g. 2 100.00
market order: <quantity> <protection price> e.g. buy 2 105.00 / sell 2 95.00
```

When the market has an executable quote, the chat prompt shows the current best ask (for buys)
or best bid (for sells) and uses it in the example so you can size orders against the visible
price.

### Prices must follow market rules

Every market enforces a price range (min/max), a tick size (the smallest price step), a
quantity range (min/max), and a price scale (decimal places). Orders outside these rules are
rejected before anything is frozen. The confirmation page shows all of these so you can check
before submitting.

### Frozen is reserved, not spent

When you place a buy order, the worst-case cost (price × quantity plus the maximum fee) is
frozen from your currency. When you place a sell order, the items or stock are frozen. The
assets page and market detail show available and frozen separately:

- A filled order consumes exactly what was traded; any unused frozen amount is released.
- A cancelled order releases the full frozen amount back to available.
- Frozen amounts cannot be used for other orders, deposits, or withdrawals until released.

### Maker vs taker fees

- **Maker**: your order rested on the book and someone traded into it. Lower fee.
- **Taker**: your order traded against an existing order. Higher fee.

The confirmation page shows the applicable rate, an estimated fee, and the estimated net
proceeds for sells. The account history shows the exact fee you paid ("My fee") as well as the
combined trade fees.

### The order book and depth

The market detail page shows:

- **Best bid / best ask**: the strongest buy and sell prices.
- **Depth rows**: each price level with quantity and value (price × quantity). More depth means
  a large order can fill without moving the price far.
- **Executable depth**: the total bid and ask quantity currently executable inside the price
  cage across the whole book — the liquidity you can hit right now.
- **Recent trades**: the latest trades with direction; green rows are aggressive buys, red rows
  are aggressive sells.
- **Candles**: switch between 1m/15m/1h/4h. Every candle icon shows its change amount and
  change percentage.

### The market list is a dashboard

The market list (`/qse` or `/qse open`) shows every market with last price, bid/ask, 24h volume,
24h turnover, status, and the most recent trade line. It supports:

- **Sorting** (24h turnover / 24h change / last price) through the sort control; markets with no
  trades sort last instead of breaking.
- **Filtering** (all / virtual securities / physical items) through the filter control.
- **Paging**: 36 markets per page with previous/next controls.
- **Icons**: emerald for virtual stocks, chest for item markets, barrier for non-open markets.
- **Colors**: 24h change is green (up), red (down), yellow (flat).

The header overview shows market count, rising/falling counts, total volume and turnover, and
the most active, top gainer, and top loser.

## Screen-by-screen guide

### Market detail (the page you will live on)

Click a market row or run `/qse market <marketId>` or `/qse stock <symbol>`. The page shows:

- Candle chart with timeframe control, 24h high/low, 24h change, volatility, spread, and volume.
- For virtual stocks: issued/total supply and float market cap (issued supply × last price).
- Your balances for that market (currency available/frozen; item or security holding).
- How much you can afford to buy at the last price (currency divided by worst-case price,
  rounded down to minimum quantity) and how much you can sell.
- Your open-order count in this market out of the per-market limit (e.g. `3 / 100`).
- Order buttons: limit buy/sell and market buy/sell. On virtual stocks they remind you that
  settlement is a ledger balance, not an item.
- Executable depth summary, depth rows, and recent trades.

Clicking an order button closes the inventory and starts a chat prompt; type the order and press
Enter to move to the confirmation page. If the market is not `OPEN`, clicking returns "This
market currently accepts queries and cancellations only."

### Order confirmation

Before anything is submitted you can review:

- The current best quote and whether a limit order will match immediately or rest.
- The fee rate, estimated fee, worst-case frozen currency, and estimated net proceeds (sells).
- Quantity range, price range, and tick size.

After confirming, you return to the market detail page. A rejected request (identity, whitelist,
permission, or market-state change) keeps you on the confirmation page so you can retry safely.
Each confirmation carries a request id and can only be claimed once.

### My Orders

`/qse orders` lists your open orders with market, side, type, remaining quantity, the frozen
currency/quantity, and the current last price for context. Orders and balances refresh
automatically when trades occur. The page is paginated and shows an explicit empty state.

Cancelling goes through a confirmation page that shows the market, side, remaining quantity, and
exactly how much frozen currency/quantity will be released. If the order already filled or was
cancelled, the page tells you instead of showing a stuck loading line.

### Assets

`/qse assets` shows, in fixed sections:

- Currency balances (available/frozen).
- Item holdings for item markets.
- Virtual securities: emerald icons with "Virtual security (ledger-only, cannot deposit or
  withdraw)", symbol, available/frozen, estimated market value, and total portfolio value.
- Recent transfers (paginated, 12 per page).

Security rows have no deposit/withdraw actions — concept stocks cannot be moved into or out of
your inventory. Clicking a security row opens its market detail page.

### Account history

`/qse history` shows trades (with direction, quantity, notional value, and the fee you paid),
transfers (with status and failure reason), and ledger entries (with reference ids), each in its
own section with paging.

### Admin page

`/qse admin` opens the operator menu for players holding any admin permission. It lists market
controls (pause/resume), order cancellation, transfer review, audit controls, and stock controls.
Only the icons for permissions you hold are shown.

## Concept stocks

Concept stocks are pure-ledger securities with a symbol (`/qse stock <symbol>` resolves it
case-insensitively), a fixed total supply, a minimum unit, and a lifecycle (`OPEN`, `PAUSED`,
`HALTED`, `CLOSED`). There is no item deposit/withdrawal: balances live only in the exchange
database. Issuance and lifecycle changes are administrator actions and are audited. See
[Virtual Concept Stocks](virtual-concept-stocks.md) for configuration and administration.

Players buy and sell them exactly like item markets, but you cannot craft, drop, or transfer the
holding outside the exchange, and a closed stock moves outstanding balances to the recovery
account.

## Getting started (5-minute walkthrough)

1. Run `/qse` and confirm the whitelist and permissions allow you in.
2. Open the assets page and deposit currency (`/qse deposit money <currency> <amount>`) or items
   (`/qse deposit item <marketId> <quantity>`). Withdrawals work the same way and need your
   inventory to have room.
3. Open the market list, sort by 24h turnover, and open a market with real volume.
4. Read the depth, last price, and 24h change. Decide on a side and a price.
5. Click limit buy, type `10 100.00`, and confirm. Your currency is now frozen at worst case.
6. Open My Orders to watch it. If it fills, your holding appears on the assets page; the unused
   frozen currency is released automatically.
7. To leave a position, sell with a limit order, or use a market order with an absolute
   protection boundary you can live with.
8. Check history to see the fees you paid and reconcile your actual P&L.

## FAQ and common pitfalls

- **Why is nothing happening to my order?** A resting limit order fills only when another player
  trades into it. The market may simply be quiet. Watch the depth and recent trades; if your
  price is worse than the best ask/bid, yours rests further back.
- **Why was my order rejected?** The quantity or price was outside the market rules (range, tick,
  min/max quantity), you hit an account limit (holding, frozen currency, open orders), or the
  market is not `OPEN`.
- **Where did my money go?** A buy order freezes the worst-case cost. Until the order fills or
  cancels, that amount is `frozen`, not lost. Cancel the order to release it.
- **Can I deposit a concept stock?** No. Stocks are ledger-only by design and never touch your
  inventory.
- **What does the protection price mean for a market order?** It is the worst absolute price you
  accept. Buying `2 105.00` means "fill up to 2 units, never paying more than 105.00 per unit,
  cancel the rest."
- **Why did my market order not fill everything?** Market orders are IOC: whatever cannot fill
  inside your boundary is cancelled, so a thin book leaves part of your quantity unfilled.
- **Why is my sell worth less than price × quantity?** Maker/taker fees are deducted. The
  confirmation page and history show the exact fee.
- **Can I trade with myself?** No. Self-trade is rejected before anything is frozen.
- **What are the audit alerts for?** The exchange watches for reciprocal high-frequency trades
  and high cancel/place ratios between the same accounts; operators are alerted and can
  acknowledge each alert with `/qse admin audit ack <alertId>`. Legitimate trading is not
  blocked by detection.

## Command reference

Player commands:

```text
/qse                       Open the market list
/qse open                  Open the market list
/qse market <marketId>     Open a market detail page
/qse stock <symbol>        Open a concept stock by symbol
/qse stocks                Open the market list
/qse order limit <buy|sell> <marketId> <price> <quantity>
/qse order market <buy|sell> <marketId> <quantity> <protectionPrice>
/qse cancel <orderId>      Cancel an open order
/qse orders                My open orders
/qse assets                Balances, holdings, and transfers
/qse history               Account history
/qse deposit|withdraw money|item ...
/qse help                  Command overview
```

Administrator commands (each requires its own `quickshop.exchange.admin.*` permission):

```text
/qse admin market pause|resume <marketId> <reason>
/qse admin order cancel <orderId> <reason>
/qse admin transfer review list|show|resolve ...
/qse admin audit status|ack <alertId>|reconcile|export <from> <to>
/qse admin stock create|issue|transfer|pause|resume|close ...
```

Tab completion covers player symbols (`/qse stock <sym>`), admin audit subcommands, and admin
stock actions plus concept-stock symbols, so you rarely need to type a raw market id.

## Permissions summary

- `quickshop.exchange.use` — open menus, view markets, history, orders, and assets.
- `quickshop.exchange.order.limit` / `quickshop.exchange.order.market` — place each order type.
- `quickshop.exchange.order.cancel` — cancel your own orders.
- `quickshop.exchange.deposit` / `quickshop.exchange.withdraw` — move currency and items.
- `quickshop.exchange.admin.market`, `quickshop.exchange.admin.orders`,
  `quickshop.exchange.admin.recovery`, `quickshop.exchange.admin.audit`,
  `quickshop.exchange.admin.stock` — operator controls shown on the admin page.

For server operators, see [Exchange Operations](exchange-operations.md) for rollout, database
and recovery, reconciliation, and production checklist guidance.
