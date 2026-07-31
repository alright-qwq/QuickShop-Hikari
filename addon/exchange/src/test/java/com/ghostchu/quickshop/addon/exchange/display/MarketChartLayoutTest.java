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
      assertInside(layout.priceAxis(), dimensions);
      assertInside(layout.timeAxis(), dimensions);
      assertInside(layout.volume(), dimensions);
      assertThat(layout.plot().width()).isGreaterThanOrEqualTo(72);
      assertThat(layout.plot().height()).isGreaterThanOrEqualTo(60);
      assertThat(layout.header().bottom()).isLessThan(layout.plot().top());
      assertThat(layout.plot().right()).isLessThan(layout.priceAxis().left());
      assertThat(layout.plot().bottom()).isLessThan(layout.volume().top());
      assertThat(layout.volume().bottom()).isLessThan(layout.timeAxis().top());
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
    assertThat(full.volume().height()).isGreaterThan(compact.volume().height());
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
