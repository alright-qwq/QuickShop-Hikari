package com.ghostchu.quickshop.addon.exchange.web;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Loopback-only HTTP server owned by the Exchange runtime lifecycle. */
public final class PublicMarketWebServer implements AutoCloseable {
  private final PublicMarketWebConfig config;
  private final PublicMarketCatalog catalog;
  private final Clock clock;
  private final BooleanSupplier ready;
  private final Duration shutdownTimeout;
  private final AtomicBoolean started = new AtomicBoolean();
  private HttpServer server;
  private ThreadPoolExecutor executor;

  public PublicMarketWebServer(PublicMarketWebConfig config, PublicMarketCatalog catalog,
                               Clock clock, BooleanSupplier ready) {
    this(config, catalog, clock, ready, Duration.ofSeconds(10));
  }

  PublicMarketWebServer(PublicMarketWebConfig config, PublicMarketCatalog catalog,
                        Clock clock, BooleanSupplier ready, Duration shutdownTimeout) {
    this.config = Objects.requireNonNull(config, "config");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ready = Objects.requireNonNull(ready, "ready");
    this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    if (shutdownTimeout.isZero() || shutdownTimeout.isNegative()) {
      throw new IllegalArgumentException("shutdownTimeout must be positive");
    }
  }

  public synchronized void start() throws IOException {
    if (!config.enabled() || !started.compareAndSet(false, true)) {
      return;
    }
    HttpServer created = null;
    ThreadPoolExecutor workers = null;
    try {
      created = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
      workers = new ThreadPoolExecutor(config.threads(), config.threads(), 0L, TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(config.maximumConcurrentRequests()),
          Thread.ofPlatform().daemon(true).name("qs-exchange-web-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());
      created.setExecutor(workers);
      created.createContext(PublicMarketHttpHandler.BASE_PATH,
          new PublicMarketHttpHandler(catalog, clock, ready, config.cacheDuration()));
      created.start();
      server = created;
      executor = workers;
    } catch (IOException | RuntimeException failure) {
      if (created != null) created.stop(0);
      if (workers != null) workers.shutdownNow();
      started.set(false);
      throw failure;
    }
  }

  public boolean running() {
    return started.get() && server != null;
  }

  @Override
  public synchronized void close() {
    if (!started.get()) {
      return;
    }
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      try {
        if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("timed out stopping public market API workers");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while stopping public market API", interrupted);
      }
      executor = null;
    }
    started.set(false);
  }
}
