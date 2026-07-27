package com.ghostchu.quickshop.addon.exchange.core.risk;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-account rolling operation limits for the order-entry boundary. */
public final class OrderRateLimiter {
  private final int perSecond;
  private final int perMinute;
  private final Map<UUID, ArrayDeque<Instant>> events = new ConcurrentHashMap<>();

  public OrderRateLimiter(int perSecond, int perMinute) {
    if (perSecond <= 0 || perMinute <= 0) {
      throw new IllegalArgumentException("rate limits must be positive");
    }
    this.perSecond = perSecond;
    this.perMinute = perMinute;
  }

  public boolean allow(UUID accountId, Instant now) {
    ArrayDeque<Instant> queue = events.computeIfAbsent(accountId, ignored -> new ArrayDeque<>());
    synchronized (queue) {
      Instant minuteCutoff = now.minusSeconds(60);
      while (!queue.isEmpty() && !queue.peekFirst().isAfter(minuteCutoff)) {
        queue.removeFirst();
      }
      long inSecond = queue.stream().filter(event -> event.isAfter(now.minusSeconds(1))).count();
      if (inSecond >= perSecond || queue.size() >= perMinute) {
        return false;
      }
      queue.addLast(now);
      return true;
    }
  }
}
