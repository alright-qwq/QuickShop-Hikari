package com.ghostchu.quickshop.addon.exchange.command;

import com.ghostchu.quickshop.addon.exchange.core.model.OrderSide;
import com.ghostchu.quickshop.addon.exchange.core.model.OrderType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeCommandRouterTest {
  @Test
  void deniesMarketOrderWithoutDedicatedPermission() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "market", "buy", "diamond-usd", "5"});
    assertThat(actor.message).isEqualTo("permission-denied");
  }

  @Test
  void requiresAnExplicitSlippageBoundaryForMarketOrders() {
    Actor actor = new Actor("quickshop.exchange.order.market");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"order", "market", "buy", "diamond-usd", "5"});

    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void generatesOneRequestIdPerConfirmedAction() {
    Actor actor = new Actor("quickshop.exchange.order.limit");
    UUID request = UUID.randomUUID();
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "buy", "diamond-usd", "100.00", "5"});
    assertThat(actor.message).isEqualTo("request-ready:" + request);
  }

  @Test
  void preservesMarketIdInsteadOfOpeningAnArbitraryMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"market", "diamond-usd"});
    assertThat(actor.opened).isNotNull();
    assertThat(actor.opened.marketId()).isEqualTo("diamond-usd");
    assertThat(actor.opened.menuName()).isEqualTo("market-detail");
  }

  @Test
  void rejectsUnknownCommandWithoutOpeningAUserControlledMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    new ExchangeCommandRouter(UUID::randomUUID).execute(actor,
        new String[] {"not-a-menu"});
    assertThat(actor.message).isEqualTo("command-invalid");
    assertThat(actor.opened).isNull();
  }

  @Test
  void parsesLimitOrderIntoAContextWithTheGeneratedRequestId() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.order.limit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"order", "limit", "sell", "diamond-usd", "100.00", "5"});
    assertThat(actor.opened.order().requestId()).isEqualTo(request);
    assertThat(actor.opened.order().side()).isEqualTo(OrderSide.SELL);
    assertThat(actor.opened.order().type()).isEqualTo(OrderType.LIMIT);
  }

  @Test
  void parsesMoneyDepositIntoAContext() {
    UUID request = UUID.randomUUID();
    Actor actor = new Actor("quickshop.exchange.deposit");
    new ExchangeCommandRouter(() -> request).execute(actor,
        new String[] {"deposit", "money", "default", "12.50"});
    assertThat(actor.opened.transfer().requestId()).isEqualTo(request);
    assertThat(actor.opened.transfer().kind())
        .isEqualTo(ExchangeMenuRequest.TransferKind.MONEY_DEPOSIT);
    assertThat(actor.opened.transfer().amount()).isEqualByComparingTo("12.50");
  }

  @Test
  void deniesEveryPlayerEntryPointOutsideTheRolloutWhitelist() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(UUID.randomUUID())));

    router.execute(actor, new String[] {"assets"});

    assertThat(actor.message).isEqualTo("rollout-not-allowed");
    assertThat(actor.opened).isNull();
  }

  @Test
  void allowsWhitelistedPlayersAndDoesNotApplyPlayerRolloutToAdministration() {
    Actor player = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(player.accountId())));
    router.execute(player, new String[] {"assets"});
    assertThat(player.opened.menuName()).isEqualTo("assets");

    Actor administrator = new Actor("quickshop.exchange.admin.audit");
    new ExchangeCommandRouter(UUID::randomUUID, null, new RolloutPolicy(true, Set.of()))
        .execute(administrator, new String[] {"admin"});
    assertThat(administrator.opened.menuName()).isEqualTo("admin");
  }

  @Test
  void opensAdminPageForAnActorWithAnyAdminPermission() {
    Actor actor = new Actor("quickshop.exchange.admin.audit");

    new ExchangeCommandRouter(UUID::randomUUID).execute(actor, new String[] {"admin"});

    assertThat(actor.opened.menuName()).isEqualTo("admin");
  }

  @Test
  void bookClaimsAfterRolloutAndUsePermissionWithoutOpeningMenu() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(actor.accountId())));

    router.execute(actor, new String[] {"book"});

    assertThat(actor.handbookClaims).isEqualTo(1);
    assertThat(actor.opened).isNull();
    assertThat(actor.message).isNull();
  }

  @Test
  void bookIsDeniedByRolloutBeforePermissionAndClaim() {
    Actor actor = new Actor();
    ExchangeCommandRouter router = new ExchangeCommandRouter(
        UUID::randomUUID, null, new RolloutPolicy(true, Set.of(UUID.randomUUID())));

    router.execute(actor, new String[] {"book"});

    assertThat(actor.message).isEqualTo("rollout-not-allowed");
    assertThat(actor.permissionsChecked).isZero();
    assertThat(actor.handbookClaims).isZero();
  }

  @Test
  void bookRequiresUsePermissionAndExactArguments() {
    Actor denied = new Actor();
    Actor invalid = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);

    router.execute(denied, new String[] {"book"});
    router.execute(invalid, new String[] {"book", "extra"});

    assertThat(denied.message).isEqualTo("permission-denied");
    assertThat(denied.handbookClaims).isZero();
    assertThat(invalid.message).isEqualTo("command-invalid");
    assertThat(invalid.handbookClaims).isZero();
  }

  @Test
  void tabCompletionIncludesBook() {
    Actor actor = new Actor("quickshop.exchange.use");
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);

    assertThat(router.tabComplete(actor, new String[0])).contains("book");
    assertThat(router.tabComplete(actor, new String[] {"bo"})).containsExactly("book");
  }

  private static final class Actor implements CommandActor {
    private final Set<String> permissions = new HashSet<>();
    private final UUID accountId = UUID.randomUUID();
    private String message;
    private ExchangeMenuRequest opened;
    private int handbookClaims;
    private int permissionsChecked;
    private Actor(String... permissions) { this.permissions.addAll(Set.of(permissions)); }
    public UUID accountId() { return accountId; }
    public boolean hasPermission(String permission) {
      permissionsChecked++;
      return permissions.contains(permission);
    }
    public void message(String key, Object... arguments) {
      message = key + (arguments.length == 0 ? "" : ":" + arguments[0]);
    }
    public void claimHandbook() { handbookClaims++; }
    public void openMenu(String menuName, int page) { }
    public void openMenu(ExchangeMenuRequest request) { opened = request; }
  }
}
