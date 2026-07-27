package com.ghostchu.quickshop.addon.exchange;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  @Override
  public void onEnable() {
    saveDefaultConfig();
    if (!getConfig().getBoolean("enabled", false)) {
      getLogger().info("QuickShop Exchange is disabled in config.yml");
      return;
    }
  }
}
