package com.ghostchu.quickshop.addon.exchange.platform;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Locale lookup with an English fallback for addon-facing messages. */
public final class AddonMessageService {
  private final Map<String, Map<String, String>> messages;

  public AddonMessageService(Map<String, Map<String, String>> messages) {
    this.messages = Map.copyOf(messages);
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
