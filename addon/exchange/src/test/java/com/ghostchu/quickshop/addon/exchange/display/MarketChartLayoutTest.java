package com.ghostchu.quickshop.addon.exchange.display;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketChartLayoutTest {
  @Test
  void keepsProfessionalRegionsInsideEverySupportedCanvas() {
    for (MarketChartDimensions dimensions : new MarketChartDimensions[] {
        new MarketChartDimensions(1, 1),
        new MarketChartDimensions(2, 1),
        new MarketChartDimensions(2, 2)}) {
      MarketChartLayout layout = MarketChartLayout.forDimensions(dimensions);

      assertInside(layout.header(), dimensions);
      assertInside(layout.plot(), dimensions);
      layout.priceAxis().ifPresent(region -> assertInside(region, dimensions));
      layout.timeAxis().ifPresent(region -> assertInside(region, dimensions));
      layout.volume().ifPresent(region -> assertInside(region, dimensions));
      assertThat(layout.plot().width()).isGreaterThanOrEqualTo(72);
      assertThat(layout.plot().height()).isGreaterThanOrEqualTo(60);
      assertThat(layout.header().bottom()).isLessThan(layout.plot().top());
      layout.priceAxis().ifPresent(
          region -> assertThat(layout.plot().right()).isLessThan(region.left()));
      layout.volume().ifPresent(
          region -> assertThat(layout.plot().bottom()).isLessThan(region.top()));
    }
  }

  @Test
  void increasesInformationDensityWithWallSize() {
    MarketChartLayout compact = MarketChartLayout.forDimensions(new MarketChartDimensions(1, 1));
    MarketChartLayout wide = MarketChartLayout.forDimensions(new MarketChartDimensions(2, 1));
    MarketChartLayout full = MarketChartLayout.forDimensions(new MarketChartDimensions(2, 2));

    assertThat(compact.density()).isEqualTo(MarketChartLayout.Density.COMPACT);
    assertThat(wide.density()).isEqualTo(MarketChartLayout.Density.WIDE);
    assertThat(full.density()).isEqualTo(MarketChartLayout.Density.FULL);
    assertThat(compact.volume()).isEmpty();
    assertThat(full.volume().orElseThrow().height())
        .isGreaterThan(wide.volume().orElseThrow().height());
  }

  private static void assertInside(MarketChartLayout.Rect rect,
                                   MarketChartDimensions dimensions) {
    assertThat(rect.left()).isGreaterThanOrEqualTo(0);
    assertThat(rect.top()).isGreaterThanOrEqualTo(0);
    assertThat(rect.right()).isLessThan(dimensions.pixelWidth());
    assertThat(rect.bottom()).isLessThan(dimensions.pixelHeight());
    assertThat(rect.width()).isPositive();
    assertThat(rect.height()).isPositive();
  }
}
