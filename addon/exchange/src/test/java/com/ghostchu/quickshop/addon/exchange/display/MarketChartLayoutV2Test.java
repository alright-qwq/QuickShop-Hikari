package com.ghostchu.quickshop.addon.exchange.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartLayoutV2Test {
  @Test
  void compactLayoutKeepsOnlyHeaderAndPlot() {
    MarketChartDimensions dimensions = new MarketChartDimensions(1, 1);
    MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);

    assertThat(layout.header()).isNotNull();
    assertThat(layout.plot()).isNotNull();
    assertThat(layout.priceAxis()).isEmpty();
    assertThat(layout.volume()).isEmpty();
    assertThat(layout.timeAxis()).isEmpty();
    assertThat(layout.legend()).isEmpty();
    assertThat(layout.confidence()).isEmpty();
    assertValidRegions(layout, dimensions);
  }

  @Test
  void wideLayoutAddsPriceAxisAndCompactVolume() {
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 1);
    MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);

    assertThat(layout.priceAxis()).isPresent();
    assertThat(layout.volume()).isPresent();
    assertThat(layout.timeAxis()).isEmpty();
    assertThat(layout.legend()).isEmpty();
    assertThat(layout.confidence()).isEmpty();
    assertValidRegions(layout, dimensions);
  }

  @Test
  void fullLayoutExposesCompleteInformationHierarchy() {
    MarketChartDimensions dimensions = new MarketChartDimensions(2, 2);
    MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);

    assertThat(layout.priceAxis()).isPresent();
    assertThat(layout.volume()).isPresent();
    assertThat(layout.timeAxis()).isPresent();
    assertThat(layout.legend()).isPresent();
    assertThat(layout.confidence()).isPresent();
    assertValidRegions(layout, dimensions);
  }

  @Test
  void trustedAndGapOptionsDefaultToEnabled() {
    MarketChartOptions options = MarketChartOptions.defaults();

    assertThat(options.showTrustedPriceLine()).isTrue();
    assertThat(options.showGapMarkers()).isTrue();
  }

  private static void assertValidRegions(MarketChartLayout layout,
                                         MarketChartDimensions dimensions) {
    List<MarketChartLayout.Rect> regions = new ArrayList<>();
    regions.add(layout.header());
    regions.add(layout.plot());
    add(layout.priceAxis(), regions);
    add(layout.volume(), regions);
    add(layout.timeAxis(), regions);
    add(layout.legend(), regions);
    add(layout.confidence(), regions);

    for (MarketChartLayout.Rect region : regions) {
      assertThat(region.left()).isGreaterThanOrEqualTo(0);
      assertThat(region.top()).isGreaterThanOrEqualTo(0);
      assertThat(region.right()).isLessThan(dimensions.pixelWidth());
      assertThat(region.bottom()).isLessThan(dimensions.pixelHeight());
    }
    for (int first = 0; first < regions.size(); first++) {
      for (int second = first + 1; second < regions.size(); second++) {
        assertThat(overlaps(regions.get(first), regions.get(second))).isFalse();
      }
    }
  }

  private static void add(Optional<MarketChartLayout.Rect> region,
                          List<MarketChartLayout.Rect> target) {
    region.ifPresent(target::add);
  }

  private static boolean overlaps(MarketChartLayout.Rect first, MarketChartLayout.Rect second) {
    return first.left() <= second.right() && second.left() <= first.right()
        && first.top() <= second.bottom() && second.top() <= first.bottom();
  }
}
