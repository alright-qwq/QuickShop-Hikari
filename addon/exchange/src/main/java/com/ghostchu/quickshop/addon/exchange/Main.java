package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.QseAliasCommand;
import com.ghostchu.quickshop.addon.exchange.command.SubCommandExchange;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntime;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuService;
import com.ghostchu.quickshop.api.command.CommandContainer;
import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  private ExchangeRuntime runtime;
  private CommandContainer exchangeCommand;
  private PluginCommand qseCommand;
  private ExchangeMenuService menus;

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
      registerPlayerEntrypoints();
    } catch (Exception failure) {
      getLogger().log(Level.SEVERE, "Exchange startup failed safely", failure);
      Bukkit.getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public void onDisable() {
    unregisterPlayerEntrypoints();
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

  private void registerPlayerEntrypoints() {
    AddonMessageService messages = AddonMessageService.load(
        new File(getDataFolder(), "messages.yml"));
    menus = new ExchangeMenuService(runtime.views());
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID);
    var actors = (java.util.function.Function<org.bukkit.entity.Player,
        com.ghostchu.quickshop.addon.exchange.command.CommandActor>) player ->
        new BukkitCommandActor(player, messages, player.locale(),
            (menu, page) -> menus.open(player, menu, page));
    exchangeCommand = CommandContainer.builder()
        .prefix("exchange")
        .permission("quickshop.exchange.use")
        .description(locale -> net.kyori.adventure.text.Component.text(
            messages.message("command-description", java.util.Locale.forLanguageTag(locale))))
        .executor(new SubCommandExchange(router, actors))
        .build();
    QuickShop.getInstance().getCommandManager().registerCmd(exchangeCommand);

    qseCommand = Objects.requireNonNull(getCommand("qse"), "qse command missing from plugin.yml");
    QseAliasCommand alias = new QseAliasCommand(router, actors);
    qseCommand.setExecutor(alias);
    qseCommand.setTabCompleter(alias);
  }

  private void unregisterPlayerEntrypoints() {
    if (exchangeCommand != null) {
      QuickShop.getInstance().getCommandManager().unregisterCmd(exchangeCommand);
      exchangeCommand = null;
    }
    if (qseCommand != null) {
      qseCommand.setExecutor(null);
      qseCommand.setTabCompleter(null);
      qseCommand = null;
    }
    if (menus != null) {
      menus.close();
      menus = null;
    }
  }
}
