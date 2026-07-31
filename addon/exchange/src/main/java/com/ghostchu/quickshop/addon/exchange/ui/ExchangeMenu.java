package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import net.tnemc.menu.core.Menu;
import net.tnemc.menu.core.PlayerInstancePage;

/** TNML menu root for the exchange. */
public final class ExchangeMenu extends Menu {
  public static final String NAME = "qs:exchange";
  public static final String TITLE = "QuickShop Exchange";

  public ExchangeMenu(ExchangeViewService views, ExchangeMenuContextStore contexts,
                      ExchangeRequestSubmitter submitter, RolloutPolicy rollout,
                      AddonMessageService messages) {
    this(views, contexts, submitter, rollout, messages, ExchangeClockDisplay.disabled());
  }

  public ExchangeMenu(ExchangeViewService views, ExchangeMenuContextStore contexts,
                      ExchangeRequestSubmitter submitter, RolloutPolicy rollout,
                      AddonMessageService messages, ExchangeClockDisplay clock) {
    name = NAME;
    title = TITLE;
    rows = 6;
    addPage(page(ExchangeMenuPage.MARKETS,
        new MarketListPage(views, contexts, messages, clock)::open));
    addPage(page(ExchangeMenuPage.MARKET_DETAIL,
        new MarketDetailPage(views, contexts, rollout, messages, clock)::open));
    addPage(page(ExchangeMenuPage.ORDER_CONFIRM,
        new RequestSummaryPage(ExchangeMenuPage.ORDER_CONFIRM, contexts, submitter, rollout,
            messages, clock)::open));
    addPage(page(ExchangeMenuPage.CANCEL_CONFIRM,
        new RequestSummaryPage(ExchangeMenuPage.CANCEL_CONFIRM, contexts, submitter, rollout,
            messages, clock)::open));
    addPage(page(ExchangeMenuPage.TRANSFER_CONFIRM,
        new RequestSummaryPage(ExchangeMenuPage.TRANSFER_CONFIRM, contexts, submitter, rollout,
            messages, clock)::open));
    addPage(page(ExchangeMenuPage.ORDERS,
        new MyOrdersPage(views, contexts, messages, clock)::open));
    addPage(page(ExchangeMenuPage.ASSETS,
        new AssetsPage(views, contexts, messages, clock)::open));
    addPage(page(ExchangeMenuPage.HISTORY,
        new HistoryPage(views, contexts, messages, clock)::open));
    addPage(page(ExchangeMenuPage.ADMIN, new AdminPage(contexts, messages, clock)::open));
  }

  private static PlayerInstancePage page(ExchangeMenuPage target,
                                         java.util.function.Consumer<net.tnemc.menu.core.callbacks.page.PageOpenCallback> open) {
    PlayerInstancePage page = ExchangePlayerPage.create(target.page());
    page.setOpen(open);
    return page;
  }
}
