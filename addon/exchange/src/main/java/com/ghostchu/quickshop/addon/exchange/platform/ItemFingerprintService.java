package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.QuickShop;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

public final class ItemFingerprintService {
  private final QuickShop quickShop;
  private final NamespacedKey transferMarker;

  public ItemFingerprintService(QuickShop quickShop, NamespacedKey transferMarker) {
    this.quickShop = quickShop;
    this.transferMarker = transferMarker;
  }

  public ItemFingerprint fingerprint(ItemStack source, FingerprintMode mode) {
    if (source == null || source.getType().isAir()) {
      throw new IllegalArgumentException("item is empty");
    }
    ItemStack normalized = normalize(source);
    if (mode == FingerprintMode.VANILLA_MATERIAL) {
      if (!acceptsVanillaMaterial(normalized, normalized.getType())) {
        throw new IllegalArgumentException("material market accepts unmodified items only");
      }
      return new ItemFingerprint("material-v1", normalized.getType().getKey().asString());
    }
    String encoded = quickShop.platform().encodeStack(normalized);
    return new ItemFingerprint("sha256-stack-v1", sha256(encoded));
  }

  public boolean acceptsVanillaMaterial(ItemStack candidate, Material material) {
    ItemStack normalized = normalize(candidate);
    ItemStack vanilla = new ItemStack(material, 1);
    return normalized.getType() == material
        && quickShop.getItemMatcher().matches(vanilla, normalized)
        && quickShop.getItemMatcher().matches(normalized, vanilla)
        && quickShop.platform().encodeStack(normalized)
            .equals(quickShop.platform().encodeStack(vanilla));
  }

  private ItemStack normalize(ItemStack source) {
    ItemStack normalized = source.clone();
    normalized.setAmount(1);
    normalized.editMeta(meta -> meta.getPersistentDataContainer().remove(transferMarker));
    return normalized;
  }

  private static String sha256(String encoded) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(encoded.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
