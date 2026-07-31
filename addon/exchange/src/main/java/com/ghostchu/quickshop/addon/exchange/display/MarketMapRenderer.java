package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

public final class MarketMapRenderer extends MapRenderer {
  private final AtomicReference<MarketChartImage> image;

  public MarketMapRenderer(MarketChartImage image) {
    super(false);
    this.image = new AtomicReference<>(requireMapImage(image));
  }

  public void update(MarketChartImage image) {
    this.image.set(requireMapImage(image));
  }

  @Override
  public void render(MapView map, MapCanvas canvas, Player player) {
    MarketChartImage current = image.get();
    for (int y = 0; y < MarketChartDimensions.MAP_SIZE; y++) {
      for (int x = 0; x < MarketChartDimensions.MAP_SIZE; x++) {
        canvas.setPixel(x, y, current.pixel(x, y));
      }
    }
  }

  private static MarketChartImage requireMapImage(MarketChartImage image) {
    Objects.requireNonNull(image, "image");
    if (image.width() != MarketChartDimensions.MAP_SIZE
        || image.height() != MarketChartDimensions.MAP_SIZE) {
      throw new IllegalArgumentException("map renderer requires a 128x128 image");
    }
    return image;
  }
}
