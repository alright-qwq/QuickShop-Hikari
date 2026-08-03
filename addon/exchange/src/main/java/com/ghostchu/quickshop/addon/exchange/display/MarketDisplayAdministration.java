package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.command.AdminCommandRouter;
import com.ghostchu.quickshop.addon.exchange.command.CommandActor;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/** Coordinates asynchronous administrator display mutations and rollback-safe persistence. */
public final class MarketDisplayAdministration
    implements AdminCommandRouter.DisplayCommands, AutoCloseable {
  private final MarketDisplayRegistry registry;
  private final MarketLookup markets;
  private final Targets targets;
  private final Refresher refresher;
  private final CheckedRunnable saver;
  private final Supplier<UUID> bindingIds;
  private final int maximumMapWalls;
  private final int maximumSigns;
  private final Executor persistenceExecutor;
  private static final Pattern MISSING_FRAME_CELL = Pattern.compile(
      "incomplete item frame wall: missing frame at column (\\d+), row (\\d+) "
          + "\\(expected block x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)");
  private BiConsumer<Throwable, String> failureReporter = (cause, context) -> { };
  private final Object operationLock = new Object();
  private CompletableFuture<Void> operationTail = CompletableFuture.completedFuture(null);
  private boolean closed;

  public MarketDisplayAdministration(MarketDisplayRegistry registry, MarketLookup markets,
                                     Targets targets, Refresher refresher,
                                     CheckedRunnable saver, Supplier<UUID> bindingIds,
                                     int maximumMapWalls, int maximumSigns) {
    this(registry, markets, targets, refresher, saver, bindingIds,
        maximumMapWalls, maximumSigns, Runnable::run);
  }

  public MarketDisplayAdministration(MarketDisplayRegistry registry, MarketLookup markets,
                                     Targets targets, Refresher refresher,
                                     CheckedRunnable saver, Supplier<UUID> bindingIds,
                                     int maximumMapWalls, int maximumSigns,
                                     Executor persistenceExecutor) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.markets = Objects.requireNonNull(markets, "markets");
    this.targets = Objects.requireNonNull(targets, "targets");
    this.refresher = Objects.requireNonNull(refresher, "refresher");
    this.saver = Objects.requireNonNull(saver, "saver");
    this.bindingIds = Objects.requireNonNull(bindingIds, "bindingIds");
    this.persistenceExecutor = Objects.requireNonNull(persistenceExecutor, "persistenceExecutor");
    if (maximumMapWalls < 0 || maximumSigns < 0) {
      throw new IllegalArgumentException("display limits must not be negative");
    }
    this.maximumMapWalls = maximumMapWalls;
    this.maximumSigns = maximumSigns;
  }

  @Override
  public void createMap(CommandActor actor, String marketId, MarketChartDimensions dimensions,
                        MarketChartMode mode, MarketChartPeriod period) {
    requireActor(actor);
    Objects.requireNonNull(dimensions, "dimensions");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(period, "period");
    if (!knownMarket(actor, marketId) || atMapLimit(actor)) {
      return;
    }
    CompletableFuture<CreatedMapWall> creation;
    try {
      creation = Objects.requireNonNull(targets.createMapWall(actor, dimensions),
          "map wall creation future");
    } catch (Exception failure) {
      report(failure, "map create");
      actor.message("display-operation-failed");
      return;
    }
    CompletableFuture<Void> operation = enqueue(actor, () -> creation.thenCompose(created -> {
      MapWallBinding binding;
      try {
        binding = new MapWallBinding(bindingIds.get(), marketId, mode, period, dimensions,
            created.frames());
      } catch (RuntimeException invalid) {
        return created.rollback().get().thenCompose(
            ignored -> CompletableFuture.failedFuture(invalid));
      }
      if (registry.mapWalls().size() >= maximumMapWalls) {
        return created.rollback().get().thenRun(() ->
            dispatchMessage(actor, "display-limit-reached"));
      }
      registry.put(binding);
      return saveAsync().handle((ignored, failure) -> {
        if (failure == null) {
          return CompletableFuture.<Void>completedFuture(null);
        }
        registry.removeMapWall(binding.bindingId());
        return created.rollback().get().thenCompose(rolledBack ->
            CompletableFuture.<Void>failedFuture(unwrap(failure)));
      }).thenCompose(java.util.function.Function.identity())
          .thenCompose(ignored -> queueRefresh(binding))
          .thenRun(() -> dispatchMessage(actor, "display-map-created"));
    }));
    reportFailure(actor, operation, "map create");
  }

  @Override
  public void mapMode(CommandActor actor, MarketChartMode mode) {
    Objects.requireNonNull(mode, "mode");
    updateTargetedMap(requireActor(actor), current -> new MapWallBinding(
        current.bindingId(), current.marketId(), mode, current.period(),
        current.dimensions(), current.frames()));
  }

  @Override
  public void mapPeriod(CommandActor actor, MarketChartPeriod period) {
    Objects.requireNonNull(period, "period");
    updateTargetedMap(requireActor(actor), current -> new MapWallBinding(
        current.bindingId(), current.marketId(), current.mode(), period,
        current.dimensions(), current.frames()));
  }

  @Override
  public void refreshMap(CommandActor actor) {
    findTargetedMap(requireActor(actor)).ifPresentOrElse(binding -> {
      queueRefresh(binding);
      actor.message("display-refresh-queued");
    }, () -> actor.message("display-target-missing"));
  }

  @Override
  public void removeMap(CommandActor actor) {
    CommandActor owner = requireActor(actor);
    Optional<UUID> selectedFrame = targets.targetedFrame(owner);
    if (selectedFrame.isEmpty()) {
      owner.message("display-target-missing");
      return;
    }
    CompletableFuture<Void> operation = enqueue(owner, () -> {
      Optional<MapWallBinding> selected = findMapByFrame(selectedFrame.get());
      if (selected.isEmpty()) {
        dispatchMessage(owner, "display-target-missing");
        return CompletableFuture.completedFuture(null);
      }
      MapWallBinding binding = selected.get();
      registry.removeMapWall(binding.bindingId());
      return saveAsync()
          .thenCompose(ignored -> targets.clearMapWall(binding))
          .handle((ignored, failure) -> {
            if (failure == null) {
              dispatchMessage(owner, "display-map-removed");
              return CompletableFuture.<Void>completedFuture(null);
            }
            registry.put(binding);
            return saveAsync().handle((restored, restoreFailure) -> {
              Throwable original = unwrap(failure);
              if (restoreFailure != null) {
                original.addSuppressed(unwrap(restoreFailure));
              }
              throw new CompletionException(original);
            }).thenApply(restored -> (Void) null);
          }).thenCompose(next -> next);
    });
    reportFailure(owner, operation, "map remove");
  }

  @Override
  public void bindSign(CommandActor actor, String marketId) {
    CommandActor owner = requireActor(actor);
    if (!knownMarket(owner, marketId) || atSignLimit(owner)) {
      return;
    }
    Optional<DisplayLocation> selected = targets.targetedSign(owner);
    if (selected.isEmpty()) {
      owner.message("display-target-missing");
      return;
    }
    DisplayLocation location = selected.get();
    CompletableFuture<Void> operation = enqueue(owner, () -> {
      if (registry.managesSign(location)) {
        dispatchMessage(owner, "display-target-invalid");
        return CompletableFuture.completedFuture(null);
      }
      if (registry.signs().size() >= maximumSigns) {
        dispatchMessage(owner, "display-limit-reached");
        return CompletableFuture.completedFuture(null);
      }
      MarketSignBinding binding = new MarketSignBinding(bindingIds.get(), marketId, location,
          MarketSignFormat.DEFAULT);
      registry.put(binding);
      return saveAsync().handle((ignored, failure) -> {
        if (failure != null) {
          registry.removeSign(binding.bindingId());
          throw new CompletionException(unwrap(failure));
        }
        queueRefresh(binding);
        dispatchMessage(owner, "display-sign-bound");
        return null;
      });
    });
    reportFailure(owner, operation, "sign bind");
  }

  @Override
  public void refreshSign(CommandActor actor) {
    findTargetedSign(requireActor(actor)).ifPresentOrElse(binding -> {
      queueRefresh(binding);
      actor.message("display-refresh-queued");
    }, () -> actor.message("display-target-missing"));
  }

  @Override
  public void removeSign(CommandActor actor) {
    CommandActor owner = requireActor(actor);
    Optional<DisplayLocation> selectedLocation = targets.targetedSign(owner);
    if (selectedLocation.isEmpty()) {
      owner.message("display-target-missing");
      return;
    }
    CompletableFuture<Void> operation = enqueue(owner, () -> {
      Optional<MarketSignBinding> selected = findSignByLocation(selectedLocation.get());
      if (selected.isEmpty()) {
        dispatchMessage(owner, "display-target-missing");
        return CompletableFuture.completedFuture(null);
      }
      MarketSignBinding binding = selected.get();
      registry.removeSign(binding.bindingId());
      return saveAsync().handle((ignored, failure) -> {
        if (failure != null) {
          registry.put(binding);
          throw new CompletionException(unwrap(failure));
        }
        dispatchMessage(owner, "display-sign-removed");
        return null;
      });
    });
    reportFailure(owner, operation, "sign remove");
  }

  private void updateTargetedMap(CommandActor actor,
                                 java.util.function.UnaryOperator<MapWallBinding> update) {
    Optional<UUID> selectedFrame = targets.targetedFrame(actor);
    if (selectedFrame.isEmpty()) {
      actor.message("display-target-missing");
      return;
    }
    CompletableFuture<Void> operation = enqueue(actor, () -> {
      Optional<MapWallBinding> selected = findMapByFrame(selectedFrame.get());
      if (selected.isEmpty()) {
        dispatchMessage(actor, "display-target-missing");
        return CompletableFuture.completedFuture(null);
      }
      MapWallBinding original = selected.get();
      MapWallBinding replacement = Objects.requireNonNull(update.apply(original), "updated binding");
      registry.put(replacement);
      return saveAsync().handle((ignored, failure) -> {
        if (failure != null) {
          registry.put(original);
          throw new CompletionException(unwrap(failure));
        }
        queueRefresh(replacement);
        dispatchMessage(actor, "display-map-updated");
        return null;
      });
    });
    reportFailure(actor, operation, "map update");
  }

  private CompletableFuture<Void> saveAsync() {
    return CompletableFuture.runAsync(() -> {
      try {
        saver.run();
      } catch (Exception failure) {
        throw new CompletionException(failure);
      }
    }, persistenceExecutor);
  }

  private CompletableFuture<Void> enqueue(
      CommandActor actor, Supplier<CompletableFuture<Void>> mutation) {
    synchronized (operationLock) {
      if (closed) {
        dispatchMessage(actor, "display-operation-failed");
        return CompletableFuture.completedFuture(null);
      }
      CompletableFuture<Void> operation = operationTail.handle((ignored, failure) -> null)
          .thenCompose(ignored -> mutation.get());
      operationTail = operation.handle((ignored, failure) -> null);
      return operation;
    }
  }

  @Override
  public void close() {
    CompletableFuture<Void> pending;
    synchronized (operationLock) {
      closed = true;
      pending = operationTail;
    }
    pending.join();
  }

  private Optional<MapWallBinding> findTargetedMap(CommandActor actor) {
    Optional<UUID> frameId = targets.targetedFrame(actor);
    return frameId.flatMap(this::findMapByFrame);
  }

  private Optional<MapWallBinding> findMapByFrame(UUID frameId) {
    return registry.mapWalls().stream()
        .filter(wall -> wall.frames().stream().anyMatch(frame -> frame.entityId().equals(frameId)))
        .findFirst();
  }

  private Optional<MarketSignBinding> findTargetedSign(CommandActor actor) {
    Optional<DisplayLocation> location = targets.targetedSign(actor);
    return location.flatMap(this::findSignByLocation);
  }

  private Optional<MarketSignBinding> findSignByLocation(DisplayLocation location) {
    return registry.signs().stream()
        .filter(sign -> sign.location().equals(location)).findFirst();
  }

  private boolean knownMarket(CommandActor actor, String marketId) {
    if (marketId == null || marketId.isBlank() || !markets.exists(marketId)) {
      actor.message("display-market-unknown");
      return false;
    }
    return true;
  }

  private boolean atMapLimit(CommandActor actor) {
    if (registry.mapWalls().size() < maximumMapWalls) return false;
    actor.message("display-limit-reached");
    return true;
  }

  private boolean atSignLimit(CommandActor actor) {
    if (registry.signs().size() < maximumSigns) return false;
    actor.message("display-limit-reached");
    return true;
  }

  private CompletableFuture<Void> queueRefresh(MapWallBinding binding) {
    return Objects.requireNonNull(refresher.refresh(binding), "display refresh future");
  }

  private void queueRefresh(MarketSignBinding binding) {
    observe(refresher.refresh(binding), "sign refresh");
  }

  private void observe(CompletableFuture<Void> refresh, String context) {
    Objects.requireNonNull(refresh, "display refresh future").exceptionally(failure -> {
      report(unwrap(failure), context);
      return null;
    });
  }

  public void failureReporter(BiConsumer<Throwable, String> failureReporter) {
    this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
  }

  private void reportFailure(CommandActor actor, CompletableFuture<Void> operation,
                             String context) {
    operation.exceptionally(failure -> {
      dispatchFailure(actor, failure);
      report(unwrap(failure), context);
      return null;
    });
  }

  private static void dispatchFailure(CommandActor actor, Throwable failure) {
    String message = String.valueOf(unwrap(failure).getMessage());
    if (message.contains("no item frame target")) {
      dispatchMessage(actor, "display-frame-target-missing");
    } else if (message.contains("incomplete item frame wall")) {
      Matcher missing = MISSING_FRAME_CELL.matcher(message);
      if (missing.find()) {
        dispatchMessage(actor, "display-frame-wall-incomplete-detail",
            missing.group(1), missing.group(2),
            missing.group(3), missing.group(4), missing.group(5));
      } else {
        dispatchMessage(actor, "display-frame-wall-incomplete");
      }
    } else if (message.contains("occupied")) {
      dispatchMessage(actor, "display-frame-occupied");
    } else if (message.contains("did not provide a new map view")) {
      dispatchMessage(actor, "display-map-view-unavailable");
    } else {
      dispatchMessage(actor, "display-operation-failed");
    }
  }

  private void report(Throwable failure, String context) {
    try {
      failureReporter.accept(failure, context);
    } catch (RuntimeException ignored) {
      // Logging must never break display operations.
    }
  }

  private static void dispatchMessage(CommandActor actor, String key, Object... arguments) {
    try {
      actor.dispatchCompletion(() -> actor.message(key, arguments));
    } catch (RuntimeException ignored) {
      // The player became unavailable while the asynchronous operation completed.
    }
  }

  private static Throwable unwrap(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
        || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static CommandActor requireActor(CommandActor actor) {
    return Objects.requireNonNull(actor, "actor");
  }

  @FunctionalInterface
  public interface MarketLookup {
    boolean exists(String marketId);
  }

  public interface Targets {
    CompletableFuture<CreatedMapWall> createMapWall(
        CommandActor actor, MarketChartDimensions dimensions) throws Exception;

    Optional<UUID> targetedFrame(CommandActor actor);

    Optional<DisplayLocation> targetedSign(CommandActor actor);

    CompletableFuture<Void> clearMapWall(MapWallBinding binding);
  }

  public interface Refresher {
    CompletableFuture<Void> refresh(MapWallBinding binding);

    CompletableFuture<Void> refresh(MarketSignBinding binding);
  }

  public record CreatedMapWall(List<MapFrameBinding> frames,
                               Supplier<CompletableFuture<Void>> rollback) {
    public CreatedMapWall {
      frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
      Objects.requireNonNull(rollback, "rollback");
    }
  }

  @FunctionalInterface
  public interface CheckedRunnable {
    void run() throws Exception;
  }
}
