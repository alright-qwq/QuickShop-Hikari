package com.ghostchu.quickshop.addon.exchange.display;

import com.ghostchu.quickshop.addon.exchange.command.CommandActor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDisplayAdministrationTest {
  @TempDir
  Path directory;

  @Test
  void createsAndPersistsMapWallThenQueuesRefresh() throws Exception {
    Fixture fixture = fixture(4, 4);
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 1);
    fixture.targets.createdFrames = frames(dimensions, fixture.worldId);

    fixture.administration.createMap(fixture.actor, "market", dimensions,
        MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);

    assertThat(fixture.registry.mapWalls()).singleElement().satisfies(binding -> {
      assertThat(binding.marketId()).isEqualTo("market");
      assertThat(binding.dimensions()).isEqualTo(dimensions);
      assertThat(binding.frames()).containsExactlyElementsOf(fixture.targets.createdFrames);
      assertThat(fixture.refreshes).containsExactly(binding);
    });
    assertThat(MarketDisplayRegistry.load(fixture.file).mapWalls()).hasSize(1);
    assertThat(fixture.actor.message).isEqualTo("display-map-created");
    assertThat(fixture.actor.completionDispatches).isEqualTo(1);
  }

  @Test
  void rejectsUnknownMarketBeforeTouchingTheWorld() throws Exception {
    Fixture fixture = fixture(4, 4);

    fixture.administration.createMap(fixture.actor, "unknown",
        new MarketChartDimensions(1, 1), MarketChartMode.LINE, MarketChartPeriod.ONE_HOUR);

    assertThat(fixture.targets.createCalls).isZero();
    assertThat(fixture.registry.mapWalls()).isEmpty();
    assertThat(fixture.actor.message).isEqualTo("display-market-unknown");
  }

  @Test
  void rollsBackCreatedMapsWhenRegistryPersistenceFails() throws Exception {
    Fixture fixture = fixture(4, 4, () -> {
      throw new java.io.IOException("disk full");
    });
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 1);
    fixture.targets.createdFrames = frames(dimensions, fixture.worldId);

    fixture.administration.createMap(fixture.actor, "market", dimensions,
        MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);

    assertThat(fixture.registry.mapWalls()).isEmpty();
    assertThat(fixture.targets.rolledBack).isEqualTo(fixture.targets.createdFrames);
    assertThat(fixture.refreshes).isEmpty();
    assertThat(fixture.actor.message).isEqualTo("display-operation-failed");
  }

  @Test
  void reportsTheRootCauseWhenMapWallCreationFails() throws Exception {
    Fixture fixture = fixture(4, 4);
    java.io.IOException failure = new java.io.IOException("map view service unavailable");
    fixture.targets.creationFailure = failure;
    Throwable[] reported = new Throwable[1];
    fixture.withReporter((cause, context) -> reported[0] = cause);

    fixture.administration.createMap(fixture.actor, "market",
        new MarketChartDimensions(1, 1), MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);

    assertThat(fixture.registry.mapWalls()).isEmpty();
    assertThat(fixture.actor.message).isEqualTo("display-operation-failed");
    assertThat(reported[0]).isSameAs(failure);
  }

  @Test
  void tellsThePlayerWhyTheMapWallCouldNotBePlaced() throws Exception {
    assertThat(messageFor(new IllegalArgumentException("no item frame target")))
        .isEqualTo("display-frame-target-missing");
    assertThat(messageFor(new IllegalArgumentException("incomplete item frame wall")))
        .isEqualTo("display-frame-wall-incomplete");
    assertThat(messageFor(new IllegalStateException("item frame wall contains occupied frames")))
        .isEqualTo("display-frame-occupied");
    assertThat(messageFor(new IllegalStateException(
        "the server did not provide a new map view for world")))
        .isEqualTo("display-map-view-unavailable");
    assertThat(messageFor(new java.io.IOException("unexpected")))
        .isEqualTo("display-operation-failed");
  }

  @Test
  void tellsThePlayerExactlyWhichFrameCellIsMissing() throws Exception {
    Fixture fixture = fixture(4, 4);
    fixture.targets.creationFailure = new IllegalArgumentException(
        "incomplete item frame wall: missing frame at column 1, row 0 (expected block "
            + "x=1, y=64, z=0, wall anchored at x=0, y=64, z=0, requested 2x1)");

    fixture.administration.createMap(fixture.actor, "market",
        new MarketChartDimensions(1, 1), MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);

    assertThat(fixture.actor.message).isEqualTo("display-frame-wall-incomplete-detail");
    assertThat(fixture.actor.messageArguments)
        .containsExactly("1", "0", "1", "64", "0");
  }

  private String messageFor(Throwable failure) {
    Fixture fixture = fixture(4, 4);
    fixture.targets.creationFailure = failure;
    fixture.administration.createMap(fixture.actor, "market",
        new MarketChartDimensions(1, 1), MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);
    return fixture.actor.message;
  }

  @Test
  void reportsRefreshFailureInsteadOfClaimingTheWallWasCreated() throws Exception {
    Fixture fixture = fixture(4, 4);
    java.io.IOException failure = new java.io.IOException("renderer unavailable");
    fixture.refreshFailure = failure;
    fixture.targets.createdFrames = frames(new MarketChartDimensions(1, 1), fixture.worldId);
    Throwable[] reported = new Throwable[1];
    fixture.withReporter((cause, context) -> reported[0] = cause);

    fixture.administration.createMap(fixture.actor, "market",
        new MarketChartDimensions(1, 1), MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);

    assertThat(fixture.registry.mapWalls()).hasSize(1);
    assertThat(fixture.actor.message).isEqualTo("display-operation-failed");
    assertThat(reported[0]).isSameAs(failure);
  }

  @Test
  void waitsForOwnerScheduledFrameCreationBeforePersisting() throws Exception {
    Fixture fixture = fixture(4, 4);
    MarketChartDimensions dimensions = new MarketChartDimensions(1, 1);
    fixture.targets.pendingCreation = new CompletableFuture<>();

    fixture.administration.createMap(fixture.actor, "market", dimensions,
        MarketChartMode.LINE, MarketChartPeriod.ONE_HOUR);

    assertThat(fixture.registry.mapWalls()).isEmpty();
    fixture.targets.pendingCreation.complete(new MarketDisplayAdministration.CreatedMapWall(
        frames(dimensions, fixture.worldId), () -> CompletableFuture.completedFuture(null)));

    assertThat(fixture.registry.mapWalls()).hasSize(1);
    assertThat(fixture.actor.message).isEqualTo("display-map-created");
  }

  @Test
  void updatesModeAndPeriodOfTargetedWallAndRefreshesImmediately() throws Exception {
    Fixture fixture = fixture(4, 4);
    MapWallBinding original = wall(fixture.worldId, MarketChartMode.KLINE,
        MarketChartPeriod.ONE_DAY);
    fixture.registry.put(original);
    fixture.registry.save();
    fixture.targets.targetedFrame = Optional.of(original.frames().getFirst().entityId());

    fixture.administration.mapMode(fixture.actor, MarketChartMode.LINE);
    fixture.administration.mapPeriod(fixture.actor, MarketChartPeriod.SEVEN_DAYS);

    MapWallBinding updated = fixture.registry.mapWall(original.bindingId()).orElseThrow();
    assertThat(updated.mode()).isEqualTo(MarketChartMode.LINE);
    assertThat(updated.period()).isEqualTo(MarketChartPeriod.SEVEN_DAYS);
    assertThat(fixture.refreshes).hasSize(2).last().isEqualTo(updated);
    assertThat(fixture.actor.message).isEqualTo("display-map-updated");
  }

  @Test
  void restoresBindingWhenClearingRemovedMapFails() throws Exception {
    Fixture fixture = fixture(4, 4);
    MapWallBinding original = wall(fixture.worldId, MarketChartMode.LINE,
        MarketChartPeriod.SIX_HOURS);
    fixture.registry.put(original);
    fixture.registry.save();
    fixture.targets.targetedFrame = Optional.of(original.frames().getFirst().entityId());
    fixture.targets.clearFailure = new IllegalStateException("frame unavailable");

    fixture.administration.removeMap(fixture.actor);

    assertThat(fixture.registry.mapWall(original.bindingId())).contains(original);
    assertThat(MarketDisplayRegistry.load(fixture.file).mapWall(original.bindingId()))
        .contains(original);
    assertThat(fixture.actor.message).isEqualTo("display-operation-failed");
  }

  @Test
  void enforcesMapAndSignCapacityLimits() throws Exception {
    Fixture fixture = fixture(1, 1);
    MapWallBinding wall = wall(fixture.worldId, MarketChartMode.LINE,
        MarketChartPeriod.ONE_HOUR);
    fixture.registry.put(wall);
    fixture.registry.put(new MarketSignBinding(UUID.randomUUID(), "market",
        new DisplayLocation(fixture.worldId, 5, 64, 5), MarketSignFormat.DEFAULT));

    fixture.administration.createMap(fixture.actor, "market",
        new MarketChartDimensions(1, 1), MarketChartMode.KLINE, MarketChartPeriod.ONE_DAY);
    assertThat(fixture.actor.message).isEqualTo("display-limit-reached");
    assertThat(fixture.targets.createCalls).isZero();

    fixture.administration.bindSign(fixture.actor, "market");
    assertThat(fixture.actor.message).isEqualTo("display-limit-reached");
    assertThat(fixture.targets.signCalls).isZero();
  }

  @Test
  void bindsRefreshesAndRemovesTargetedSign() throws Exception {
    Fixture fixture = fixture(4, 4);
    DisplayLocation location = new DisplayLocation(fixture.worldId, 8, 70, 9);
    fixture.targets.targetedSign = Optional.of(location);

    fixture.administration.bindSign(fixture.actor, "market");
    MarketSignBinding binding = fixture.registry.signs().getFirst();
    assertThat(binding.location()).isEqualTo(location);
    assertThat(fixture.signRefreshes).containsExactly(binding);
    assertThat(fixture.actor.message).isEqualTo("display-sign-bound");

    fixture.administration.refreshSign(fixture.actor);
    assertThat(fixture.signRefreshes).containsExactly(binding, binding);
    assertThat(fixture.actor.message).isEqualTo("display-refresh-queued");

    fixture.administration.removeSign(fixture.actor);
    assertThat(fixture.registry.signs()).isEmpty();
    assertThat(MarketDisplayRegistry.load(fixture.file).signs()).isEmpty();
    assertThat(fixture.actor.message).isEqualTo("display-sign-removed");
  }

  @Test
  void serializesMutationsUntilTheirPersistenceCompletes() throws Exception {
    Fixture fixture = fixture(4, 4);
    MapWallBinding original = wall(fixture.worldId, MarketChartMode.KLINE,
        MarketChartPeriod.ONE_DAY);
    fixture.registry.put(original);
    fixture.targets.targetedFrame = Optional.of(original.frames().getFirst().entityId());
    QueuedExecutor persistence = new QueuedExecutor();
    fixture.rebuildAdministration(persistence);

    fixture.administration.mapMode(fixture.actor, MarketChartMode.LINE);
    fixture.administration.mapPeriod(fixture.actor, MarketChartPeriod.SEVEN_DAYS);

    assertThat(fixture.registry.mapWall(original.bindingId()).orElseThrow().mode())
        .isEqualTo(MarketChartMode.LINE);
    assertThat(fixture.registry.mapWall(original.bindingId()).orElseThrow().period())
        .isEqualTo(MarketChartPeriod.ONE_DAY);
    assertThat(persistence.queued()).isEqualTo(1);

    persistence.runNext();

    assertThat(fixture.registry.mapWall(original.bindingId()).orElseThrow().period())
        .isEqualTo(MarketChartPeriod.SEVEN_DAYS);
    assertThat(persistence.queued()).isEqualTo(1);
    persistence.runNext();
    assertThat(MarketDisplayRegistry.load(fixture.file).mapWall(original.bindingId()).orElseThrow())
        .isEqualTo(fixture.registry.mapWall(original.bindingId()).orElseThrow());
  }

  @Test
  void closeWaitsForAcceptedPersistenceAndRejectsLateMutations() throws Exception {
    Fixture fixture = fixture(4, 4);
    DisplayLocation location = new DisplayLocation(fixture.worldId, 8, 70, 9);
    fixture.targets.targetedSign = Optional.of(location);
    CountDownLatch saveStarted = new CountDownLatch(1);
    CountDownLatch releaseSave = new CountDownLatch(1);
    fixture.saver = () -> {
      saveStarted.countDown();
      if (!releaseSave.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("save was not released");
      }
      fixture.registry.save();
    };
    fixture.rebuildAdministration(command -> Thread.ofPlatform().start(command));

    fixture.administration.bindSign(fixture.actor, "market");
    assertThat(saveStarted.await(2, TimeUnit.SECONDS)).isTrue();
    Thread closer = Thread.ofPlatform().start(fixture.administration::close);
    Thread.sleep(50L);

    assertThat(closer.isAlive()).isTrue();
    fixture.administration.bindSign(fixture.actor, "market");
    assertThat(fixture.actor.message).isEqualTo("display-operation-failed");
    releaseSave.countDown();
    closer.join(2_000L);
    assertThat(closer.isAlive()).isFalse();
    assertThat(MarketDisplayRegistry.load(fixture.file).signs()).hasSize(1);
  }

  private Fixture fixture(int maxWalls, int maxSigns) {
    final Fixture[] holder = new Fixture[1];
    Fixture fixture = new Fixture(directory.resolve("displays.yml"), maxWalls, maxSigns, null);
    holder[0] = fixture;
    fixture.saver = fixture.registry::save;
    fixture.rebuildAdministration();
    return fixture;
  }

  private Fixture fixture(int maxWalls, int maxSigns,
                          MarketDisplayAdministration.CheckedRunnable saver) {
    return new Fixture(directory.resolve("displays.yml"), maxWalls, maxSigns, saver);
  }

  private static List<MapFrameBinding> frames(MarketChartDimensions dimensions, UUID worldId) {
    List<MapFrameBinding> frames = new ArrayList<>();
    for (int index = 0; index < dimensions.columns() * dimensions.rows(); index++) {
      frames.add(new MapFrameBinding(UUID.randomUUID(), worldId, index, 64, 0, index + 10));
    }
    return List.copyOf(frames);
  }

  private static MapWallBinding wall(UUID worldId, MarketChartMode mode,
                                     MarketChartPeriod period) {
    return new MapWallBinding(UUID.randomUUID(), "market", mode, period,
        new MarketChartDimensions(1, 1), frames(new MarketChartDimensions(1, 1), worldId));
  }

  private static final class Fixture {
    private final Path file;
    private final UUID worldId = UUID.randomUUID();
    private final MarketDisplayRegistry registry;
    private final Targets targets = new Targets();
    private final Actor actor = new Actor();
    private final List<MapWallBinding> refreshes = new ArrayList<>();
    private final List<MarketSignBinding> signRefreshes = new ArrayList<>();
    private Throwable refreshFailure;
    private final int maxWalls;
    private final int maxSigns;
    private MarketDisplayAdministration.CheckedRunnable saver;
    private MarketDisplayAdministration administration;
    private java.util.function.BiConsumer<Throwable, String> reporter =
        (cause, context) -> { };

    private Fixture(Path file, int maxWalls, int maxSigns,
                    MarketDisplayAdministration.CheckedRunnable saver) {
      this.file = file;
      this.maxWalls = maxWalls;
      this.maxSigns = maxSigns;
      this.registry = MarketDisplayRegistry.load(file);
      this.saver = saver == null ? registry::save : saver;
      rebuildAdministration();
    }

    private void rebuildAdministration() {
      rebuildAdministration(Runnable::run);
    }

    private void rebuildAdministration(Executor persistenceExecutor) {
      administration = new MarketDisplayAdministration(registry, Set.of("market")::contains,
          targets, new MarketDisplayAdministration.Refresher() {
            @Override
            public CompletableFuture<Void> refresh(MapWallBinding binding) {
              refreshes.add(binding);
              return refreshFailure == null
                  ? CompletableFuture.completedFuture(null)
                  : CompletableFuture.failedFuture(refreshFailure);
            }

            @Override
            public CompletableFuture<Void> refresh(MarketSignBinding binding) {
              signRefreshes.add(binding);
              return CompletableFuture.completedFuture(null);
            }
          }, saver, UUID::randomUUID, maxWalls, maxSigns, persistenceExecutor);
      administration.failureReporter(reporter);
    }

    private void withReporter(java.util.function.BiConsumer<Throwable, String> reporter) {
      this.reporter = reporter;
      rebuildAdministration();
    }
  }

  private static final class QueuedExecutor implements Executor {
    private final java.util.ArrayDeque<Runnable> commands = new java.util.ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      commands.addLast(command);
    }

    private int queued() {
      return commands.size();
    }

    private void runNext() {
      commands.removeFirst().run();
    }
  }

  private static final class Targets implements MarketDisplayAdministration.Targets {
    private List<MapFrameBinding> createdFrames = List.of();
    private List<MapFrameBinding> rolledBack = List.of();
    private Throwable creationFailure;
    private CompletableFuture<MarketDisplayAdministration.CreatedMapWall> pendingCreation;
    private Optional<UUID> targetedFrame = Optional.empty();
    private Optional<DisplayLocation> targetedSign = Optional.empty();
    private RuntimeException clearFailure;
    private int createCalls;
    private int signCalls;

    @Override
    public CompletableFuture<MarketDisplayAdministration.CreatedMapWall> createMapWall(
        CommandActor actor, MarketChartDimensions dimensions) {
      createCalls++;
      if (pendingCreation != null) {
        return pendingCreation;
      }
      if (creationFailure != null) {
        return CompletableFuture.failedFuture(creationFailure);
      }
      return CompletableFuture.completedFuture(new MarketDisplayAdministration.CreatedMapWall(
          createdFrames, () -> {
            rolledBack = createdFrames;
            return CompletableFuture.completedFuture(null);
          }));
    }

    @Override
    public Optional<UUID> targetedFrame(CommandActor actor) {
      return targetedFrame;
    }

    @Override
    public Optional<DisplayLocation> targetedSign(CommandActor actor) {
      signCalls++;
      return targetedSign;
    }

    @Override
    public CompletableFuture<Void> clearMapWall(MapWallBinding binding) {
      return clearFailure == null
          ? CompletableFuture.completedFuture(null)
          : CompletableFuture.failedFuture(clearFailure);
    }
  }

  private static final class Actor implements CommandActor {
    private final UUID id = UUID.randomUUID();
    private String message;
    private Object[] messageArguments = new Object[0];
    private int completionDispatches;

    @Override public UUID accountId() { return id; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public boolean isPlayer() { return true; }
    @Override public void message(String key, Object... arguments) {
      message = key;
      messageArguments = arguments;
    }
    @Override public void dispatchCompletion(Runnable completion) {
      completionDispatches++;
      completion.run();
    }
    @Override public void openMenu(String menuName, int page) { }
  }
}
