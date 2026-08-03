package com.ghostchu.quickshop.addon.exchange.web;

import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Routes the intentionally small, GET-only public market API. */
public final class PublicMarketHttpHandler implements HttpHandler {
  public static final String BASE_PATH = "/api/v1/public";
  private final PublicMarketCatalog catalog;
  private final Clock clock;
  private final BooleanSupplier ready;
  private final int cacheSeconds;
  private final Duration cacheDuration;
  private final Map<String, CachedJson> cache = new ConcurrentHashMap<>();

  public PublicMarketHttpHandler(PublicMarketCatalog catalog, Clock clock, BooleanSupplier ready) {
    this(catalog, clock, ready, Duration.ofSeconds(3));
  }

  public PublicMarketHttpHandler(PublicMarketCatalog catalog, Clock clock,
                                 BooleanSupplier ready, Duration cacheDuration) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ready = Objects.requireNonNull(ready, "ready");
    this.cacheDuration = Objects.requireNonNull(cacheDuration, "cacheDuration");
    if (cacheDuration.isZero() || cacheDuration.isNegative()
        || cacheDuration.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("cacheDuration must be between 1 second and 1 minute");
    }
    this.cacheSeconds = Math.max(1, Math.toIntExact(cacheDuration.toSeconds()));
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Objects.requireNonNull(exchange, "exchange");
    addSecurityHeaders(exchange.getResponseHeaders());
    String method = exchange.getRequestMethod();
    boolean head = "HEAD".equals(method);
    if (!"GET".equals(method) && !head) {
      exchange.getResponseHeaders().set("Allow", "GET, HEAD");
      send(exchange, 405, PublicMarketJson.error("method_not_allowed", "only GET and HEAD are supported"), head, false);
      return;
    }
    URI request = exchange.getRequestURI();
    if (request.toASCIIString().length() > 1024) {
      send(exchange, 414, PublicMarketJson.error("uri_too_long", "request URI is too long"), head, false);
      return;
    }
    try {
      route(exchange, request, head);
    } catch (IllegalArgumentException failure) {
      send(exchange, 400, PublicMarketJson.error("invalid_request", failure.getMessage()), head, false);
    } catch (RuntimeException failure) {
      send(exchange, 503, PublicMarketJson.error("service_unavailable", "market data is temporarily unavailable"), head, false);
    }
  }

  private void route(HttpExchange exchange, URI request, boolean head) throws IOException {
    String path = request.getPath();
    if ((BASE_PATH + "/health").equals(path)) {
      requireNoQuery(request);
      boolean serviceReady = ready.getAsBoolean();
      send(exchange, serviceReady ? 200 : 503,
          PublicMarketJson.health(serviceReady, catalog.markets().size(), clock.instant()), head, false);
      return;
    }
    if ((BASE_PATH + "/markets").equals(path)) {
      requireNoQuery(request);
      if (!ready.getAsBoolean()) {
        sendUnavailable(exchange, head);
        return;
      }
      send(exchange, 200, PublicMarketJson.markets(catalog.markets()), head, true);
      return;
    }
    String prefix = BASE_PATH + "/markets/";
    String suffix = "/snapshot";
    if (path.startsWith(prefix) && path.endsWith(suffix)) {
      if (!ready.getAsBoolean()) {
        sendUnavailable(exchange, head);
        return;
      }
      String marketId = path.substring(prefix.length(), path.length() - suffix.length());
      if (!catalog.contains(marketId)) {
        send(exchange, 404, PublicMarketJson.error("not_found", "unknown public market"), head, false);
        return;
      }
      Map<String, String> query = query(request);
      if (!query.keySet().equals(java.util.Set.of("period"))) {
        throw new IllegalArgumentException("snapshot requires exactly one period parameter");
      }
      MarketChartPeriod period = MarketChartPeriod.parse(query.get("period"));
      Instant now = clock.instant();
      String cacheKey = marketId + ':' + period.token();
      CachedJson cached = cache.get(cacheKey);
      if (cached == null || !now.isBefore(cached.expiresAt())) {
        var snapshot = catalog.snapshot(marketId, period, now).join();
        cached = new CachedJson(PublicMarketJson.snapshot(snapshot), now.plus(cacheDuration));
        cache.put(cacheKey, cached);
      }
      send(exchange, 200, cached.json(), head, true);
      return;
    }
    send(exchange, 404, PublicMarketJson.error("not_found", "public API route not found"), head, false);
  }

  private static void requireNoQuery(URI request) {
    if (request.getRawQuery() != null) {
      throw new IllegalArgumentException("this endpoint does not accept query parameters");
    }
  }

  private static Map<String, String> query(URI request) {
    if (request.getRawQuery() == null || request.getRawQuery().isBlank()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (String part : request.getRawQuery().split("&", -1)) {
      int separator = part.indexOf('=');
      if (separator <= 0 || separator == part.length() - 1) {
        throw new IllegalArgumentException("invalid query parameter");
      }
      String key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
      if (values.putIfAbsent(key, value) != null) {
        throw new IllegalArgumentException("duplicate query parameter: " + key);
      }
    }
    return Map.copyOf(values);
  }

  private void sendUnavailable(HttpExchange exchange, boolean head) throws IOException {
    send(exchange, 503,
        PublicMarketJson.error("service_unavailable", "exchange runtime is not ready"),
        head, false);
  }

  private void send(HttpExchange exchange, int status, String json, boolean head, boolean cacheable)
      throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    Headers headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", cacheable
        ? "public, max-age=" + cacheSeconds + ", stale-if-error=30"
        : "no-store");
    if (head) {
      headers.set("Content-Length", Integer.toString(body.length));
      exchange.sendResponseHeaders(status, -1L);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    try (var output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private record CachedJson(String json, Instant expiresAt) {}

  private static void addSecurityHeaders(Headers headers) {
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Content-Security-Policy", "default-src 'none'");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("X-Frame-Options", "DENY");
    headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  }
}
