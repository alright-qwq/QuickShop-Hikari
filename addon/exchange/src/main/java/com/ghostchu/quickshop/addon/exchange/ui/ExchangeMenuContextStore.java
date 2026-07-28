package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.command.ExchangeMenuRequest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps typed command state attached to a player while a TNML viewer is open. */
public final class ExchangeMenuContextStore implements AutoCloseable {
  private final Map<UUID, ExchangeMenuRequest> requests = new ConcurrentHashMap<>();

  public void put(UUID playerId, ExchangeMenuRequest request) {
    requests.put(Objects.requireNonNull(playerId, "playerId"),
        Objects.requireNonNull(request, "request"));
  }

  public Optional<ExchangeMenuRequest> get(UUID playerId) {
    return Optional.ofNullable(requests.get(Objects.requireNonNull(playerId, "playerId")));
  }

  public Optional<ExchangeMenuRequest> remove(UUID playerId) {
    return Optional.ofNullable(requests.remove(Objects.requireNonNull(playerId, "playerId")));
  }

  @Override
  public void close() {
    requests.clear();
  }
}
