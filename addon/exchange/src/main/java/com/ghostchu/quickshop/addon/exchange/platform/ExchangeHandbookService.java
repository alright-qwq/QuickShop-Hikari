package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/** Creates, authenticates and safely inserts portable Exchange handbooks. */
public final class ExchangeHandbookService {
  public static final String HANDBOOK_VERSION = "v1";

  private final NamespacedKey handbookKey;
  private final ExchangeHandbookSettings settings;
  private final AddonMessageService messages;
  private final Function<Player, Locale> localeResolver;

  public ExchangeHandbookService(
      NamespacedKey handbookKey,
      ExchangeHandbookSettings settings,
      AddonMessageService messages,
      Function<Player, Locale> localeResolver) {
    this.handbookKey = Objects.requireNonNull(handbookKey, "handbookKey");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.messages = Objects.requireNonNull(messages, "messages");
    this.localeResolver = Objects.requireNonNull(localeResolver, "localeResolver");
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public ItemStack createItem(Player player) {
    Objects.requireNonNull(player, "player");
    Locale locale = locale(player);
    ItemStack handbook = new ItemStack(settings.material());
    handbook.editMeta(meta -> {
      meta.displayName(Component.text(messages.message("handbook-title", locale)));
      meta.lore(List.of(
          Component.text(messages.message("handbook-lore-1", locale)),
          Component.text(messages.message("handbook-lore-2", locale))));
      meta.getPersistentDataContainer().set(
          handbookKey, PersistentDataType.STRING, HANDBOOK_VERSION);
    });
    return handbook;
  }

  public boolean isHandbook(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return false;
    }
    return HANDBOOK_VERSION.equals(item.getItemMeta()
        .getPersistentDataContainer()
        .get(handbookKey, PersistentDataType.STRING));
  }

  public GiveResult claim(Player player) {
    Objects.requireNonNull(player, "player");
    if (!settings.enabled()) {
      return reply(player, GiveResult.DISABLED, "handbook-disabled");
    }
    if (!settings.selfClaim()) {
      return reply(player, GiveResult.SELF_CLAIM_DISABLED, "handbook-self-claim-disabled");
    }
    return give(player, false);
  }

  public GiveResult give(Player player, boolean allowDuplicate) {
    Objects.requireNonNull(player, "player");
    if (!settings.enabled()) {
      return reply(player, GiveResult.DISABLED, "handbook-disabled");
    }
    if (!allowDuplicate && settings.preventDuplicate() && ownsHandbook(player)) {
      return reply(player, GiveResult.ALREADY_OWNED, "handbook-already-owned");
    }

    ItemStack handbook = createItem(player);
    PlayerInventory inventory = player.getInventory();
    if (!canFit(inventory.getStorageContents(), handbook)) {
      return reply(player, GiveResult.NO_SPACE, "handbook-inventory-full");
    }
    ItemStack[] original = copyContents(inventory.getStorageContents());
    try {
      Map<Integer, ItemStack> leftovers = inventory.addItem(handbook);
      if (leftovers.isEmpty()) {
        return reply(player, GiveResult.SUCCESS, "handbook-claim-success");
      }
    } catch (RuntimeException ignored) {
      // Restore the storage snapshot below if the platform partially mutated it.
    }
    inventory.setStorageContents(original);
    return reply(player, GiveResult.NO_SPACE, "handbook-inventory-full");
  }

  private boolean ownsHandbook(Player player) {
    return Arrays.stream(player.getInventory().getContents()).anyMatch(this::isHandbook);
  }

  private static boolean canFit(ItemStack[] storage, ItemStack addition) {
    int remaining = addition.getAmount();
    for (ItemStack stack : storage) {
      if (stack != null && stack.isSimilar(addition)) {
        int capacity = Math.min(stack.getMaxStackSize(), addition.getMaxStackSize())
            - stack.getAmount();
        remaining -= Math.min(remaining, Math.max(capacity, 0));
      }
      if (remaining == 0) {
        return true;
      }
    }
    for (ItemStack stack : storage) {
      if (stack == null || stack.getType().isAir()) {
        remaining -= Math.min(remaining, addition.getMaxStackSize());
      }
      if (remaining == 0) {
        return true;
      }
    }
    return false;
  }

  private static ItemStack[] copyContents(ItemStack[] contents) {
    return Arrays.stream(contents)
        .map(stack -> stack == null ? null : stack.clone())
        .toArray(ItemStack[]::new);
  }

  private GiveResult reply(Player player, GiveResult result, String messageKey) {
    player.sendMessage(messages.message(messageKey, locale(player)));
    return result;
  }

  private Locale locale(Player player) {
    Locale locale = localeResolver.apply(player);
    return locale == null ? Locale.US : locale;
  }

  public enum GiveResult {
    SUCCESS,
    DISABLED,
    SELF_CLAIM_DISABLED,
    ALREADY_OWNED,
    NO_SPACE
  }
}
