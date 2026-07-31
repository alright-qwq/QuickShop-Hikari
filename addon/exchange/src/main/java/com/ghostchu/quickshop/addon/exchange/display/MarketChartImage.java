package com.ghostchu.quickshop.addon.exchange.display;

import java.util.Arrays;
import java.util.Objects;

public final class MarketChartImage {
  private final int width;
  private final int height;
  private final byte[] pixels;

  public MarketChartImage(int width, int height, byte[] pixels) {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("image dimensions must be positive");
    }
    Objects.requireNonNull(pixels, "pixels");
    if (pixels.length != Math.multiplyExact(width, height)) {
      throw new IllegalArgumentException("pixel count does not match image dimensions");
    }
    this.width = width;
    this.height = height;
    this.pixels = pixels.clone();
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public byte[] pixels() {
    return pixels.clone();
  }

  public byte pixel(int x, int y) {
    if (x < 0 || x >= width || y < 0 || y >= height) {
      throw new IndexOutOfBoundsException("pixel outside image");
    }
    return pixels[y * width + x];
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MarketChartImage image
        && width == image.width && height == image.height && Arrays.equals(pixels, image.pixels);
  }

  @Override
  public int hashCode() {
    return 31 * (31 * width + height) + Arrays.hashCode(pixels);
  }
}
