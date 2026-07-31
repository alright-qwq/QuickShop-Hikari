package com.ghostchu.quickshop.addon.exchange.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MarketChartSlices {
  private MarketChartSlices() {}

  public static List<MarketChartImage> slice(MarketChartImage source,
                                              MarketChartDimensions dimensions) {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(dimensions, "dimensions");
    if (source.width() != dimensions.pixelWidth()
        || source.height() != dimensions.pixelHeight()) {
      throw new IllegalArgumentException("source image does not match chart wall dimensions");
    }
    List<MarketChartImage> result = new ArrayList<>(dimensions.columns() * dimensions.rows());
    for (int row = 0; row < dimensions.rows(); row++) {
      for (int column = 0; column < dimensions.columns(); column++) {
        byte[] tile = new byte[MarketChartDimensions.MAP_SIZE * MarketChartDimensions.MAP_SIZE];
        for (int y = 0; y < MarketChartDimensions.MAP_SIZE; y++) {
          for (int x = 0; x < MarketChartDimensions.MAP_SIZE; x++) {
            int sourceX = column * MarketChartDimensions.MAP_SIZE + x;
            int sourceY = row * MarketChartDimensions.MAP_SIZE + y;
            tile[y * MarketChartDimensions.MAP_SIZE + x] = source.pixel(sourceX, sourceY);
          }
        }
        result.add(new MarketChartImage(
            MarketChartDimensions.MAP_SIZE, MarketChartDimensions.MAP_SIZE, tile));
      }
    }
    return List.copyOf(result);
  }
}
