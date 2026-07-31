package com.ghostchu.quickshop.addon.exchange.display;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MarketDisplayRegistry {
  private final Path file;
  private final Map<UUID, MapWallBinding> mapWalls;
  private final Map<UUID, MarketSignBinding> signs;
  private final List<String> diagnostics;

  private MarketDisplayRegistry(Path file, Map<UUID, MapWallBinding> mapWalls,
                                Map<UUID, MarketSignBinding> signs, List<String> diagnostics) {
    this.file = Objects.requireNonNull(file, "file");
    this.mapWalls = new LinkedHashMap<>(mapWalls);
    this.signs = new LinkedHashMap<>(signs);
    this.diagnostics = new ArrayList<>(diagnostics);
  }

  public static MarketDisplayRegistry load(Path file) {
    Objects.requireNonNull(file, "file");
    Map<UUID, MapWallBinding> walls = new LinkedHashMap<>();
    Map<UUID, MarketSignBinding> signs = new LinkedHashMap<>();
    List<String> diagnostics = new ArrayList<>();
    if (!Files.exists(file)) {
      return new MarketDisplayRegistry(file, walls, signs, diagnostics);
    }
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
    readWalls(yaml.getConfigurationSection("maps"), walls, diagnostics);
    readSigns(yaml.getConfigurationSection("signs"), signs, diagnostics);
    return new MarketDisplayRegistry(file, walls, signs, diagnostics);
  }

  public synchronized List<MapWallBinding> mapWalls() {
    return List.copyOf(mapWalls.values());
  }

  public synchronized List<MarketSignBinding> signs() {
    return List.copyOf(signs.values());
  }

  public synchronized List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }

  public synchronized Optional<MapWallBinding> mapWall(UUID bindingId) {
    return Optional.ofNullable(mapWalls.get(Objects.requireNonNull(bindingId, "bindingId")));
  }

  public synchronized Optional<MarketSignBinding> sign(UUID bindingId) {
    return Optional.ofNullable(signs.get(Objects.requireNonNull(bindingId, "bindingId")));
  }

  public synchronized boolean managesFrame(UUID entityId) {
    Objects.requireNonNull(entityId, "entityId");
    return mapWalls.values().stream()
        .flatMap(wall -> wall.frames().stream())
        .anyMatch(frame -> frame.entityId().equals(entityId));
  }

  public synchronized boolean managesSign(DisplayLocation location) {
    Objects.requireNonNull(location, "location");
    return signs.values().stream().anyMatch(sign -> sign.location().equals(location));
  }

  public synchronized List<MapWallBinding> mapWallsInChunk(UUID worldId, int chunkX, int chunkZ) {
    Objects.requireNonNull(worldId, "worldId");
    return mapWalls.values().stream()
        .filter(wall -> wall.frames().stream().anyMatch(frame ->
            frame.worldId().equals(worldId)
                && Math.floorDiv(frame.x(), 16) == chunkX
                && Math.floorDiv(frame.z(), 16) == chunkZ))
        .toList();
  }

  public synchronized List<MarketSignBinding> signsInChunk(UUID worldId, int chunkX, int chunkZ) {
    Objects.requireNonNull(worldId, "worldId");
    return signs.values().stream().filter(sign ->
        sign.location().worldId().equals(worldId)
            && Math.floorDiv(sign.location().x(), 16) == chunkX
            && Math.floorDiv(sign.location().z(), 16) == chunkZ).toList();
  }

  public synchronized void put(MapWallBinding binding) {
    mapWalls.put(binding.bindingId(), Objects.requireNonNull(binding, "binding"));
  }

  public synchronized void put(MarketSignBinding binding) {
    signs.put(binding.bindingId(), Objects.requireNonNull(binding, "binding"));
  }

  public synchronized Optional<MapWallBinding> removeMapWall(UUID bindingId) {
    return Optional.ofNullable(mapWalls.remove(Objects.requireNonNull(bindingId, "bindingId")));
  }

  public synchronized Optional<MarketSignBinding> removeSign(UUID bindingId) {
    return Optional.ofNullable(signs.remove(Objects.requireNonNull(bindingId, "bindingId")));
  }

  public synchronized void save() throws IOException {
    YamlConfiguration yaml = new YamlConfiguration();
    for (MapWallBinding wall : mapWalls.values()) {
      String root = "maps." + wall.bindingId();
      yaml.set(root + ".market", wall.marketId());
      yaml.set(root + ".mode", wall.mode().name().toLowerCase(java.util.Locale.ROOT));
      yaml.set(root + ".period", wall.period().token());
      yaml.set(root + ".dimensions", wall.dimensions().columns() + "x" + wall.dimensions().rows());
      List<Map<String, Object>> frames = new ArrayList<>();
      for (MapFrameBinding frame : wall.frames()) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("entity", frame.entityId().toString());
        stored.put("world", frame.worldId().toString());
        stored.put("x", frame.x());
        stored.put("y", frame.y());
        stored.put("z", frame.z());
        stored.put("map-id", frame.mapId());
        frames.add(stored);
      }
      yaml.set(root + ".frames", frames);
    }
    for (MarketSignBinding sign : signs.values()) {
      String root = "signs." + sign.bindingId();
      yaml.set(root + ".market", sign.marketId());
      yaml.set(root + ".format", sign.format().name().toLowerCase(java.util.Locale.ROOT));
      yaml.set(root + ".world", sign.location().worldId().toString());
      yaml.set(root + ".x", sign.location().x());
      yaml.set(root + ".y", sign.location().y());
      yaml.set(root + ".z", sign.location().z());
    }
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
    boolean moved = false;
    try {
      yaml.save(temporary.toFile());
      try {
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      moved = true;
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static void readWalls(ConfigurationSection section, Map<UUID, MapWallBinding> target,
                                List<String> diagnostics) {
    if (section == null) {
      return;
    }
    for (String key : section.getKeys(false)) {
      try {
        ConfigurationSection node = requiredSection(section, key);
        UUID id = UUID.fromString(key);
        MarketChartDimensions dimensions = MarketChartDimensions.parse(node.getString("dimensions"));
        List<MapFrameBinding> frames = readFrames(node.getMapList("frames"));
        target.put(id, new MapWallBinding(id, requiredString(node, "market"),
            MarketChartMode.parse(node.getString("mode")),
            MarketChartPeriod.parse(node.getString("period")), dimensions, frames));
      } catch (RuntimeException invalid) {
        diagnostics.add("maps." + key + ": " + invalid.getMessage());
      }
    }
  }

  private static void readSigns(ConfigurationSection section, Map<UUID, MarketSignBinding> target,
                                List<String> diagnostics) {
    if (section == null) {
      return;
    }
    for (String key : section.getKeys(false)) {
      try {
        ConfigurationSection node = requiredSection(section, key);
        UUID id = UUID.fromString(key);
        DisplayLocation location = new DisplayLocation(
            UUID.fromString(requiredString(node, "world")),
            node.getInt("x"), node.getInt("y"), node.getInt("z"));
        target.put(id, new MarketSignBinding(id, requiredString(node, "market"), location,
            MarketSignFormat.parse(node.getString("format", "default"))));
      } catch (RuntimeException invalid) {
        diagnostics.add("signs." + key + ": " + invalid.getMessage());
      }
    }
  }

  private static List<MapFrameBinding> readFrames(List<Map<?, ?>> storedFrames) {
    List<MapFrameBinding> frames = new ArrayList<>();
    for (Map<?, ?> stored : storedFrames) {
      frames.add(new MapFrameBinding(
          UUID.fromString(requiredMapString(stored, "entity")),
          UUID.fromString(requiredMapString(stored, "world")),
          mapInt(stored, "x"), mapInt(stored, "y"), mapInt(stored, "z"),
          mapInt(stored, "map-id")));
    }
    return List.copyOf(frames);
  }

  private static ConfigurationSection requiredSection(ConfigurationSection parent, String key) {
    ConfigurationSection section = parent.getConfigurationSection(key);
    if (section == null) {
      throw new IllegalArgumentException("binding must be a section");
    }
    return section;
  }

  private static String requiredString(ConfigurationSection section, String key) {
    String value = section.getString(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private static String requiredMapString(Map<?, ?> values, String key) {
    Object value = values.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return text;
  }

  private static int mapInt(Map<?, ?> values, String key) {
    Object value = values.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException(key + " must be an integer");
  }
}
