package com.ghostchu.quickshop.addon.exchange.ui;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Validated, immutable GUI clock configuration without a periodic refresh task. */
public final class ExchangeClockDisplay {
  public static final String DEFAULT_ZONE_ID = "Asia/Shanghai";
  public static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm";

  private final boolean enabled;
  private final ZoneId zone;
  private final DateTimeFormatter formatter;
  private final Clock clock;

  private ExchangeClockDisplay(boolean enabled, ZoneId zone,
                               DateTimeFormatter formatter, Clock clock) {
    this.enabled = enabled;
    this.zone = zone;
    this.formatter = formatter;
    this.clock = clock;
  }

  public static ExchangeClockDisplay defaults() {
    return create(true, DEFAULT_ZONE_ID, DEFAULT_PATTERN, Clock.systemUTC(), ignored -> {});
  }

  public static ExchangeClockDisplay disabled() {
    return create(false, DEFAULT_ZONE_ID, DEFAULT_PATTERN, Clock.systemUTC(), ignored -> {});
  }

  public static ExchangeClockDisplay create(boolean enabled, String configuredZone,
                                             String configuredPattern, Clock clock,
                                             Consumer<String> warningConsumer) {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(warningConsumer, "warningConsumer");
    ZoneId zone = validZone(configuredZone, warningConsumer);
    DateTimeFormatter formatter = validFormatter(configuredPattern, warningConsumer);
    return new ExchangeClockDisplay(enabled, zone, formatter, clock);
  }

  public Optional<DisplayTime> now() {
    if (!enabled) {
      return Optional.empty();
    }
    return Optional.of(new DisplayTime(
        formatter.format(clock.instant().atZone(zone)), zone.getId()));
  }

  private static ZoneId validZone(String configured, Consumer<String> warnings) {
    try {
      return ZoneId.of(configured == null || configured.isBlank() ? DEFAULT_ZONE_ID : configured);
    } catch (DateTimeException invalid) {
      warnings.accept("Invalid gui.clock.zone-id '" + configured
          + "'; using " + DEFAULT_ZONE_ID);
      return ZoneId.of(DEFAULT_ZONE_ID);
    }
  }

  private static DateTimeFormatter validFormatter(String configured, Consumer<String> warnings) {
    String pattern = configured == null || configured.isBlank() ? DEFAULT_PATTERN : configured;
    try {
      return DateTimeFormatter.ofPattern(pattern);
    } catch (IllegalArgumentException invalid) {
      warnings.accept("Invalid gui.clock.format '" + configured
          + "'; using " + DEFAULT_PATTERN);
      return DateTimeFormatter.ofPattern(DEFAULT_PATTERN);
    }
  }

  public record DisplayTime(String text, String zoneId) {
    public DisplayTime {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(zoneId, "zoneId");
    }
  }
}
