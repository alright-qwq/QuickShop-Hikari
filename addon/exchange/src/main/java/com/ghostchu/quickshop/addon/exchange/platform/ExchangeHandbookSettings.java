package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Locale;
import org.bukkit.Material;

/** Validated immutable settings for the portable Exchange handbook. */
public record ExchangeHandbookSettings(
    boolean enabled,
    boolean selfClaim,
    boolean preventDuplicate,
    Material material) {

  public static final Material DEFAULT_MATERIAL = Material.KNOWLEDGE_BOOK;

  public ExchangeHandbookSettings {
    material = material == null || material.isAir() || !material.isItem()
        ? DEFAULT_MATERIAL
        : material;
  }

  public static ExchangeHandbookSettings create(
      boolean enabled,
      boolean selfClaim,
      boolean preventDuplicate,
      String configuredMaterial) {
    Material material = configuredMaterial == null
        ? null
        : Material.matchMaterial(configuredMaterial.trim().toUpperCase(Locale.ROOT));
    return new ExchangeHandbookSettings(
        enabled, selfClaim, preventDuplicate, material);
  }
}
