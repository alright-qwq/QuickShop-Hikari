package com.ghostchu.quickshop.addon.exchange;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.addon.exchange.command.BukkitCommandActor;
import com.ghostchu.quickshop.addon.exchange.command.AdminCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import com.ghostchu.quickshop.addon.exchange.command.QseAliasCommand;
import com.ghostchu.quickshop.addon.exchange.command.RolloutPolicy;
import com.ghostchu.quickshop.addon.exchange.command.SubCommandExchange;
import com.ghostchu.quickshop.addon.exchange.platform.AddonMessageService;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntime;
import com.ghostchu.quickshop.addon.exchange.runtime.ExchangeRuntimeFactory;
import com.ghostchu.quickshop.addon.exchange.runtime.RuntimeExchangeRequestSubmitter;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuListener;
import com.ghostchu.quickshop.addon.exchange.ui.ExchangeMenuService;
import com.ghostchu.quickshop.api.command.CommandContainer;
import java.io.File;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {
  private ExchangeRuntime runtime;
  private CommandContainer exchangeCommand;
  private PluginCommand qseCommand;
  private ExchangeMenuService menus;
  private ExchangeMenuListener menuListener;

  static java.util.List<String> firstRunResources() {
    return java.util.List.of("markets.yml", "messages.yml");
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();
    for (String resource : firstRunResources()) {
      if (!new File(getDataFolder(), resource).isFile()) {
        saveResource(resource, false);
      }
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
    ExchangeRuntime closingRuntime = runtime;
    ExchangeShutdown.RuntimeHandle runtimeHandle = closingRuntime == null ? null
        : new ExchangeShutdown.RuntimeHandle() {
          @Override
          public void close() throws Exception {
            closingRuntime.close();
          }

          @Override
          public boolean closed() {
            return closingRuntime.closed();
          }
        };
    ExchangeShutdown.Result result = ExchangeShutdown.close(runtimeHandle,
        this::unregisterCommands,
        this::closeMenus,
        this::unregisterMenuListener);
    if (result.failure() != null) {
      getLogger().log(Level.SEVERE, "Exchange shutdown failed", result.failure());
    }
    if (result.runtimeClosed()) {
      runtime = null;
    }
  }

  private void registerPlayerEntrypoints() {
    AddonMessageService messages = AddonMessageService.load(
        new File(getDataFolder(), "messages.yml"));
    RolloutPolicy rollout = rolloutPolicy();
    menus = new ExchangeMenuService(runtime.views(), new RuntimeExchangeRequestSubmitter(runtime),
        rollout, messages);
    menuListener = new ExchangeMenuListener(menus);
    Bukkit.getPluginManager().registerEvents(menuListener, this);
    ExchangeCommandRouter router = new ExchangeCommandRouter(UUID::randomUUID,
        new AdminCommandRouter(runtime.administration(), UUID::randomUUID,
            work -> runtime.runWhileWriting(work::run), runtime.transferReviews()), rollout);
    var actors = (java.util.function.Function<org.bukkit.entity.Player,
        com.ghostchu.quickshop.addon.exchange.command.CommandActor>) player ->
        new BukkitCommandActor(player, messages, player.locale(),
            new BukkitCommandActor.MenuOpener() {
              @Override
              public void open(String menu, int page) {
                menus.open(player, menu, page);
              }

              @Override
              public void open(ExchangeMenuRequest request) {
                menus.open(player, request);
              }
            });
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

  private RolloutPolicy rolloutPolicy() {
    boolean enabled = getConfig().getBoolean("rollout.whitelist-enabled", true);
    java.util.Set<UUID> allowed = new java.util.HashSet<>();
    for (String value : getConfig().getStringList("rollout.allowed-players")) {
      try {
        allowed.add(UUID.fromString(value.trim()));
      } catch (IllegalArgumentException invalid) {
        throw new IllegalArgumentException("invalid rollout player UUID: " + value, invalid);
      }
    }
    return new RolloutPolicy(enabled, allowed);
  }

  private void unregisterCommands() {
    if (exchangeCommand != null) {
      QuickShop.getInstance().getCommandManager().unregisterCmd(exchangeCommand);
      exchangeCommand = null;
    }
    if (qseCommand != null) {
      qseCommand.setExecutor(null);
      qseCommand.setTabCompleter(null);
      qseCommand = null;
    }
  }

  private void closeMenus() {
    if (menus != null) {
      ExchangeMenuService closingMenus = menus;
      closingMenus.close();
      menus = null;
    }
  }

  private void unregisterMenuListener() {
    if (menuListener != null) {
      HandlerList.unregisterAll(menuListener);
      menuListener = null;
    }
  }
}
