package com.ghostchu.quickshop.addon.exchange.display;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartSlicesTest {
  @Test
  void slicesTwoByOneImageFromLeftToRight() {
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 1);
    MarketChartImage source = quadrants(dimensions);

    List<MarketChartImage> slices = MarketChartSlices.slice(source, dimensions);

    assertThat(slices).hasSize(2);
    assertTile(slices.get(0), (byte) 1);
    assertTile(slices.get(1), (byte) 2);
  }

  @Test
  void slicesTwoByTwoImageLeftToRightThenTopToBottom() {
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 2);
    MarketChartImage source = quadrants(dimensions);

    List<MarketChartImage> slices = MarketChartSlices.slice(source, dimensions);

    assertThat(slices).hasSize(4);
    assertTile(slices.get(0), (byte) 1);
    assertTile(slices.get(1), (byte) 2);
    assertTile(slices.get(2), (byte) 3);
    assertTile(slices.get(3), (byte) 4);
  }

  @Test
  void rejectsSourceWhoseDimensionsDoNotMatchTheWall() {
    MarketChartImage source = new MarketChartImage(128, 128, new byte[128 * 128]);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        MarketChartSlices.slice(source, new MarketChartDimensions(2, 1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MarketChartImage quadrants(MarketChartDimensions dimensions) {
    byte[] pixels = new byte[dimensions.pixelWidth() * dimensions.pixelHeight()];
    for (int y = 0; y < dimensions.pixelHeight(); y++) {
      for (int x = 0; x < dimensions.pixelWidth(); x++) {
        int column = x / MarketChartDimensions.MAP_SIZE;
        int row = y / MarketChartDimensions.MAP_SIZE;
        pixels[y * dimensions.pixelWidth() + x] = (byte) (row * dimensions.columns() + column + 1);
      }
    }
    return new MarketChartImage(dimensions.pixelWidth(), dimensions.pixelHeight(), pixels);
  }

  private static void assertTile(MarketChartImage image, byte expected) {
    assertThat(image.width()).isEqualTo(128);
    assertThat(image.height()).isEqualTo(128);
    assertThat(image.pixels()).containsOnly(expected);
  }
}
