package com.ghostchu.quickshop.addon.exchange.display;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.map.MapCanvas;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketMapRendererTest {
  @Test
  void copiesExactlyOneHundredTwentyEightSquaredCachedPixels() {
    byte[] pixels = new byte[128 * 128];
    for (int index = 0; index < pixels.length; index++) {
      pixels[index] = (byte) (index % 120);
    }
    MarketMapRenderer renderer = new MarketMapRenderer(new MarketChartImage(128, 128, pixels));
    AtomicInteger writes = new AtomicInteger();
    MapCanvas canvas = (MapCanvas) Proxy.newProxyInstance(
        MapCanvas.class.getClassLoader(), new Class<?>[]{MapCanvas.class},
        (proxy, method, args) -> {
          if (method.getName().equals("setPixel")) {
            int x = (Integer) args[0];
            int y = (Integer) args[1];
            byte color = (Byte) args[2];
            assertThat(color).isEqualTo(pixels[y * 128 + x]);
            writes.incrementAndGet();
          }
          return null;
        });

    renderer.render(null, canvas, null);

    assertThat(writes).hasValue(128 * 128);
  }

  @Test
  void subsequentRenderUsesUpdatedImageOnly() {
    MarketMapRenderer renderer = new MarketMapRenderer(image((byte) 1));
    renderer.update(image((byte) 2));
    AtomicInteger oldPixels = new AtomicInteger();
    AtomicInteger newPixels = new AtomicInteger();
    MapCanvas canvas = (MapCanvas) Proxy.newProxyInstance(
        MapCanvas.class.getClassLoader(), new Class<?>[]{MapCanvas.class},
        (proxy, method, args) -> {
          if (method.getName().equals("setPixel")) {
            byte color = (Byte) args[2];
            if (color == 1) oldPixels.incrementAndGet();
            if (color == 2) newPixels.incrementAndGet();
          }
          return null;
        });

    renderer.render(null, canvas, null);

    assertThat(oldPixels).hasValue(0);
    assertThat(newPixels).hasValue(128 * 128);
  }

  private static MarketChartImage image(byte color) {
    byte[] pixels = new byte[128 * 128];
    java.util.Arrays.fill(pixels, color);
    return new MarketChartImage(128, 128, pixels);
  }
}
