package com.ghostchu.quickshop.addon.exchange.platform;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeHandbookServiceTest {
  private static ServerMock server;
  private static final NamespacedKey HANDBOOK_KEY =
      new NamespacedKey("exchange", "handbook");

  @BeforeAll
  static void startServer() {
    server = MockBukkit.mock();
  }

  @AfterAll
  static void stopServer() {
    MockBukkit.unmock();
  }

  @BeforeEach
  void clearPlayers() {
    server.getOnlinePlayers().forEach(player -> player.kick());
  }

  @Test
  void createsVersionedHandbookThatCanBeAuthenticated() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService service = service(settings(true, true, true));

    ItemStack handbook = service.createItem(player);

    assertThat(handbook.getType()).isEqualTo(Material.KNOWLEDGE_BOOK);
    assertThat(handbook.getItemMeta().getPersistentDataContainer()
        .get(HANDBOOK_KEY, PersistentDataType.STRING)).isEqualTo("v1");
    assertThat(service.isHandbook(handbook)).isTrue();
  }

  @Test
  void renamedOrdinaryBookIsNotAuthenticated() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService service = service(settings(true, true, true));
    ItemStack imitation = new ItemStack(Material.KNOWLEDGE_BOOK);
    imitation.editMeta(meta -> meta.setDisplayName("Exchange Trading Handbook"));

    assertThat(service.isHandbook(imitation)).isFalse();
    assertThat(service.isHandbook(null)).isFalse();
    assertThat(service.isHandbook(new ItemStack(Material.AIR))).isFalse();
  }

  @Test
  void selfClaimRejectsDuplicateAnywhereInStorage() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService service = service(settings(true, true, true));
    player.getInventory().setItem(17, service.createItem(player));

    ExchangeHandbookService.GiveResult result = service.claim(player);

    assertThat(result).isEqualTo(ExchangeHandbookService.GiveResult.ALREADY_OWNED);
    assertThat(handbookCount(player, service)).isEqualTo(1);
    assertThat(player.nextMessage()).isEqualTo("Already owned");
  }

  @Test
  void fullInventoryRejectsHandbookWithoutDroppingOrChangingInventory() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService service = service(settings(true, true, false));
    for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
      player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }

    ExchangeHandbookService.GiveResult result = service.claim(player);

    assertThat(result).isEqualTo(ExchangeHandbookService.GiveResult.NO_SPACE);
    assertThat(handbookCount(player, service)).isZero();
    assertThat(player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class)).isEmpty();
    assertThat(player.nextMessage()).isEqualTo("Inventory full");
  }

  @Test
  void disabledFeatureAndDisabledSelfClaimAreRejectedSeparately() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService disabled = service(settings(false, true, true));
    ExchangeHandbookService noSelfClaim = service(settings(true, false, true));

    assertThat(disabled.claim(player))
        .isEqualTo(ExchangeHandbookService.GiveResult.DISABLED);
    assertThat(noSelfClaim.claim(player))
        .isEqualTo(ExchangeHandbookService.GiveResult.SELF_CLAIM_DISABLED);
    assertThat(handbookCount(player, disabled)).isZero();
  }

  @Test
  void administratorGiveCanAllowDuplicateButStillHonorsCapacity() {
    PlayerMock player = server.addPlayer();
    ExchangeHandbookService service = service(settings(true, true, true));

    assertThat(service.give(player, true))
        .isEqualTo(ExchangeHandbookService.GiveResult.SUCCESS);
    assertThat(service.give(player, true))
        .isEqualTo(ExchangeHandbookService.GiveResult.SUCCESS);

    assertThat(handbookCount(player, service)).isEqualTo(2);
  }

  @Test
  void invalidConfiguredMaterialFallsBackToKnowledgeBook() {
    ExchangeHandbookSettings settings =
        ExchangeHandbookSettings.create(true, true, true, "NOT_A_REAL_MATERIAL");

    assertThat(settings.material()).isEqualTo(Material.KNOWLEDGE_BOOK);
  }

  private static ExchangeHandbookSettings settings(
      boolean enabled, boolean selfClaim, boolean preventDuplicate) {
    return ExchangeHandbookSettings.create(
        enabled, selfClaim, preventDuplicate, "KNOWLEDGE_BOOK");
  }

  private static ExchangeHandbookService service(ExchangeHandbookSettings settings) {
    AddonMessageService messages = new AddonMessageService(Map.of(
        "en-US", Map.of(
            "handbook-title", "Exchange Trading Handbook",
            "handbook-lore-1", "Right click to open Exchange",
            "handbook-lore-2", "This signed handbook can be traded",
            "handbook-claim-success", "Handbook received",
            "handbook-already-owned", "Already owned",
            "handbook-inventory-full", "Inventory full",
            "handbook-disabled", "Handbook disabled",
            "handbook-self-claim-disabled", "Self claim disabled")));
    return new ExchangeHandbookService(
        HANDBOOK_KEY, settings, messages, ignored -> Locale.US);
  }

  private static long handbookCount(PlayerMock player, ExchangeHandbookService service) {
    return java.util.Arrays.stream(player.getInventory().getStorageContents())
        .filter(service::isHandbook)
        .mapToLong(ItemStack::getAmount)
        .sum();
  }
}
