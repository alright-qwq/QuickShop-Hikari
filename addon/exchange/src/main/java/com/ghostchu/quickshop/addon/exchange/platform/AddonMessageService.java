package com.ghostchu.quickshop.addon.exchange.platform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Locale lookup with an English fallback for addon-facing messages. */
public final class AddonMessageService {
  private final Map<String, Map<String, String>> messages;

  public AddonMessageService(Map<String, Map<String, String>> messages) {
    this.messages = Map.copyOf(messages);
  }

  public static AddonMessageService load(File source) {
    Objects.requireNonNull(source, "source");
    return fromYaml(YamlConfiguration.loadConfiguration(source));
  }

  public static AddonMessageService load(File defaults, File overrides) {
    Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(overrides, "overrides");
    YamlConfiguration merged = YamlConfiguration.loadConfiguration(defaults);
    mergeInto(merged, YamlConfiguration.loadConfiguration(overrides));
    return fromYaml(merged);
  }

  public static AddonMessageService load(InputStream defaults, File overrides) {
    Objects.requireNonNull(defaults, "defaults");
    Objects.requireNonNull(overrides, "overrides");
    try (InputStreamReader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
      YamlConfiguration merged = YamlConfiguration.loadConfiguration(reader);
      mergeInto(merged, YamlConfiguration.loadConfiguration(overrides));
      return fromYaml(merged);
    } catch (IOException failure) {
      throw new UncheckedIOException("failed to close bundled messages", failure);
    }
  }

  private static AddonMessageService fromYaml(YamlConfiguration yaml) {
    Map<String, Map<String, String>> localized = new LinkedHashMap<>();
    for (String locale : yaml.getKeys(false)) {
      ConfigurationSection section = yaml.getConfigurationSection(locale);
      if (section == null) {
        continue;
      }
      Map<String, String> entries = new LinkedHashMap<>();
      for (String key : section.getKeys(false)) {
        String value = section.getString(key);
        if (value != null) {
          entries.put(key, value);
        }
      }
      localized.put(locale, Map.copyOf(entries));
    }
    if (!localized.containsKey("en-US")) {
      throw new IllegalArgumentException("messages.yml must define en-US");
    }
    return new AddonMessageService(localized);
  }

  private static void mergeInto(YamlConfiguration defaults, YamlConfiguration overrides) {
    for (String path : overrides.getKeys(true)) {
      if (!overrides.isConfigurationSection(path)) {
        defaults.set(path, overrides.get(path));
      }
    }
  }

  public String message(String key, Locale locale, Object... arguments) {
    Map<String, String> localized = messages.getOrDefault(locale.toLanguageTag(), messages.get("en-US"));
    String template = Objects.requireNonNull(localized, "missing en-US messages").get(key);
    if (template == null) template = messages.get("en-US").getOrDefault(key, key);
    for (int index = 0; index < arguments.length; index++) {
      template = template.replace("<" + index + ">", String.valueOf(arguments[index]));
    }
    if (arguments.length > 0) {
      template = template.replace("<requestId>", String.valueOf(arguments[0]));
    }
    return template;
  }
}
