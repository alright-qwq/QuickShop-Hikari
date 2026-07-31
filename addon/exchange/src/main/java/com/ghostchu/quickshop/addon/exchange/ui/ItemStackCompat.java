package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.QuickShop;
import java.lang.reflect.Method;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.tnemc.item.AbstractItemStack;

/**
 * Compatibility layer for TNML AbstractItemStack name setting.
 * 6.2.0.11 uses display(Component), 6.3.0.0+ uses customName(Component).
 * 6.3.0.0 API only has customName, so display must be called via reflection.
 */
final class ItemStackCompat {
  private static volatile Method displayMethod;
  private static volatile Boolean useDisplay = null;

  private ItemStackCompat() {}

  @SuppressWarnings("rawtypes")
  static AbstractItemStack of(String material, Component name) {
    AbstractItemStack item = QuickShop.getInstance().stack().of(material, 1);
    return name(item, name);
  }

  @SuppressWarnings("rawtypes")
  static AbstractItemStack of(String material, Component name, List<Component> lore) {
    return of(material, name).lore(lore);
  }

  @SuppressWarnings("rawtypes")
  static AbstractItemStack name(AbstractItemStack item, Component name) {
    if (item == null) return null;
    Boolean useDisplayNow = useDisplay;
    if (useDisplayNow == null) {
      // First call: try customName (6.3.0.0 API), catch NoSuchMethodError for 6.2.0.11
      try {
        item.customName(name);
        useDisplay = false;
        return item;
      } catch (NoSuchMethodError e) {
        useDisplay = true;
        log("customName not found, falling back to display()");
        return nameViaDisplay(item, name);
      } catch (Exception e) {
        log("customName failed: " + e.getMessage() + ", trying display()");
        useDisplay = true;
        return nameViaDisplay(item, name);
      }
    } else if (useDisplayNow) {
      return nameViaDisplay(item, name);
    } else {
      item.customName(name);
      return item;
    }
  }

  private static void log(String message) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("qssuite-exchange");
    if (plugin != null) {
      plugin.getLogger().info("[ItemStackCompat] " + message);
    }
  }

  @SuppressWarnings("rawtypes")
  private static AbstractItemStack nameViaDisplay(AbstractItemStack item, Component name) {
    try {
      Method resolved = displayMethod;
      if (resolved == null) {
        resolved = item.getClass().getMethod("display", Component.class);
        displayMethod = resolved;
      }
      return (AbstractItemStack) resolved.invoke(item, name);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set item name via display()", e);
    }
  }
}
