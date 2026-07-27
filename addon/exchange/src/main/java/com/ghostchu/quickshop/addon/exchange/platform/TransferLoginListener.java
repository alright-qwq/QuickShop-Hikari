package com.ghostchu.quickshop.addon.exchange.platform;

import com.ghostchu.quickshop.addon.exchange.transfer.TransferRecoveryService;
import java.util.Objects;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Starts recovery asynchronously after the player has joined. */
public final class TransferLoginListener implements Listener {
  private final TransferRecoveryService recovery;

  public TransferLoginListener(TransferRecoveryService recovery) {
    this.recovery = Objects.requireNonNull(recovery, "recovery");
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    recovery.recoverPlayer(event.getPlayer().getUniqueId());
  }
}
