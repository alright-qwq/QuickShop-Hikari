package com.ghostchu.quickshop.addon.exchange.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/** Safe resource bounds for the public read-only HTTP listener. */
public record PublicMarketWebConfig(boolean enabled, String bindAddress, int port,
                                    Duration cacheDuration, int threads,
                                    int maximumConcurrentRequests) {
  public PublicMarketWebConfig {
    bindAddress = Objects.requireNonNull(bindAddress, "bindAddress").trim();
    Objects.requireNonNull(cacheDuration, "cacheDuration");
    if (bindAddress.isEmpty() || !loopback(bindAddress)) {
      throw new IllegalArgumentException("public market API must bind to a loopback address");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("public market API port must be between 1 and 65535");
    }
    if (cacheDuration.isZero() || cacheDuration.isNegative()
        || cacheDuration.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("public market cache must be between 1 second and 1 minute");
    }
    if (threads < 1 || threads > 16 || maximumConcurrentRequests < threads
        || maximumConcurrentRequests > 256) {
      throw new IllegalArgumentException("invalid public market API resource limits");
    }
  }

  public static PublicMarketWebConfig defaults() {
    return new PublicMarketWebConfig(false, "127.0.0.1", 8765,
        Duration.ofSeconds(3), 2, 16);
  }

  private static boolean loopback(String address) {
    try {
      return InetAddress.getByName(address).isLoopbackAddress();
    } catch (UnknownHostException failure) {
      throw new IllegalArgumentException("invalid public market API bind address", failure);
    }
  }
}
