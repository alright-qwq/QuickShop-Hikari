package com.ghostchu.quickshop.addon.exchange.display;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MarketDisplayService implements AutoCloseable {
  private final MarketDisplayDataSource dataSource;
  private final MarketChartSeriesBuilder seriesBuilder;
  private final MarketChartRenderer renderer;
  private final MarketChartCache cache;
  private final MarketSignFormatter signFormatter;
  private final DisplayScheduler scheduler;
  private final Clock clock;
  private final Map<UUID, RefreshState> refreshes = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public MarketDisplayService(MarketDisplayDataSource dataSource,
                              MarketChartSeriesBuilder seriesBuilder,
                              MarketChartRenderer renderer,
                              MarketChartCache cache,
                              MarketSignFormatter signFormatter,
                              DisplayScheduler scheduler,
                              Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.seriesBuilder = Objects.requireNonNull(seriesBuilder, "seriesBuilder");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.signFormatter = Objects.requireNonNull(signFormatter, "signFormatter");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CompletableFuture<Void> refresh(MapWallBinding binding) {
    Objects.requireNonNull(binding, "binding");
    return coalesced(binding.bindingId(), () -> refreshMap(binding));
  }

  public CompletableFuture<Void> refresh(MarketSignBinding binding) {
    Objects.requireNonNull(binding, "binding");
    return coalesced(binding.bindingId(), () -> refreshSign(binding));
  }

  private CompletableFuture<Void> refreshMap(MapWallBinding binding) {
    return dataSource.snapshot(binding.marketId(), binding.period(), clock.instant())
        .thenCompose(snapshot -> {
          MarketChartSeries series = seriesBuilder.build(snapshot.candles(), snapshot.trustedPoints(),
              binding.dimensions(), binding.period(), snapshot.liquidityTier());
          MarketChartCache.Key key = new MarketChartCache.Key(binding.marketId(), binding.mode(),
              binding.period(), binding.dimensions(), snapshot.fingerprint(),
              renderer.optionsFingerprint(), snapshot.trustedStateVersion(), series.interval());
          MarketChartImage image = cache.getOrRender(key, () -> renderer.render(
              series, binding.mode(),
              binding.dimensions(), snapshot.displayName(), binding.period(),
              snapshot.quote().lastPrice(), snapshot.quote().change24h()));
          List<MarketChartImage> slices = MarketChartSlices.slice(image, binding.dimensions());
          CompletableFuture<?>[] updates = new CompletableFuture<?>[binding.frames().size()];
          for (int index = 0; index < binding.frames().size(); index++) {
            updates[index] = scheduler.updateMapFrame(binding.frames().get(index), slices.get(index));
          }
          return CompletableFuture.allOf(updates);
        });
  }

  private CompletableFuture<Void> refreshSign(MarketSignBinding binding) {
    return dataSource.snapshot(binding.marketId(), MarketChartPeriod.ONE_DAY, clock.instant())
        .thenCompose(snapshot -> scheduler.updateSign(binding,
            signFormatter.format(snapshot.displayName(), snapshot.quote())));
  }

  private CompletableFuture<Void> coalesced(UUID bindingId,
                                             java.util.function.Supplier<CompletableFuture<Void>> work) {
    if (closed.get()) {
      throw new IllegalStateException("market display service is closed");
    }
    RefreshState state = refreshes.computeIfAbsent(bindingId, ignored -> new RefreshState());
    synchronized (state) {
      if (state.running != null && !state.running.isDone()) {
        state.pending = true;
        return state.running;
      }
      state.work = work;
      state.running = run(state, bindingId);
      return state.running;
    }
  }

  private CompletableFuture<Void> run(RefreshState state, UUID bindingId) {
    CompletableFuture<Void> current;
    try {
      current = Objects.requireNonNull(state.work.get(), "display refresh future");
    } catch (RuntimeException failure) {
      current = CompletableFuture.failedFuture(failure);
    }
    return current.whenComplete((ignored, failure) -> complete(state, bindingId));
  }

  private void complete(RefreshState state, UUID bindingId) {
    java.util.function.Supplier<CompletableFuture<Void>> followUp = null;
    synchronized (state) {
      if (!closed.get() && state.pending) {
        state.pending = false;
        followUp = state.work;
      } else {
        state.running = null;
        refreshes.remove(bindingId, state);
      }
    }
    if (followUp != null) {
      CompletableFuture<Void> next;
      try {
        next = Objects.requireNonNull(followUp.get(), "display follow-up future");
      } catch (RuntimeException failure) {
        next = CompletableFuture.failedFuture(failure);
      }
      synchronized (state) {
        state.running = next.whenComplete((ignored, failure) -> complete(state, bindingId));
      }
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      IllegalStateException failure = new IllegalStateException("market display service is closed");
      for (RefreshState state : refreshes.values()) {
        synchronized (state) {
          state.pending = false;
          if (state.running != null && !state.running.isDone()) {
            state.running.completeExceptionally(failure);
          }
        }
      }
      refreshes.clear();
      cache.close();
    }
  }

  private static final class RefreshState {
    private java.util.function.Supplier<CompletableFuture<Void>> work;
    private CompletableFuture<Void> running;
    private boolean pending;
  }
}
