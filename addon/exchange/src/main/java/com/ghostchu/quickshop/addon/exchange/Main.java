package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntime;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  private ExchangeRuntime runtime;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    if (!new java.io.File(getDataFolder(), "markets.yml").isFile()) {
      saveResource("markets.yml", false);
    }
    if (!getConfig().getBoolean("enabled", false)) {
      getLogger().info("QuickShop Exchange is disabled in config.yml");
      return;
    }
    try {
      runtime = new ExchangeRuntimeFactory(this, QuickShop.getInstance()).create();
      runtime.start();
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE, "Exchange startup failed safely", failure);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    if (runtime == null) {
      return;
    }
    try {
      runtime.close();
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE, "Exchange shutdown failed", failure);
    } finally {
      runtime = null;
    }
  }
}
