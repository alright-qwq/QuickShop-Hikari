package com.ghostchu.quickshop.addon.exchange.web;

import com.ghostchu.quickshop.addon.exchange.display.MarketChartPeriod;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicMarketHttpHandlerTest {
  private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

  @Test
  void servesHealthMarketListAndSnapshotsWithSecurityHeaders() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    PublicMarketCatalog catalog = catalog(reads);
    PublicMarketHttpHandler handler = new PublicMarketHttpHandler(catalog,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> true);

    FakeExchange health = request("GET", "/api/v1/public/health");
    handler.handle(health);
    assertThat(health.status).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"ok\"");

    FakeExchange markets = request("GET", "/api/v1/public/markets");
    handler.handle(markets);
    assertThat(markets.status).isEqualTo(200);
    assertThat(markets.body()).contains("diamond-usd", "钻石");

    FakeExchange snapshot = request("GET", "/api/v1/public/markets/diamond-usd/snapshot?period=6h");
    handler.handle(snapshot);
    assertThat(snapshot.status).isEqualTo(200);
    assertThat(snapshot.body()).contains("\"marketId\":\"diamond-usd\"");
    assertThat(snapshot.responseHeaders.getFirst("Content-Type"))
        .isEqualTo("application/json; charset=utf-8");
    assertThat(snapshot.responseHeaders.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(snapshot.responseHeaders.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'");
    assertThat(snapshot.responseHeaders.getFirst("Cache-Control")).contains("max-age=3");
    assertThat(reads).hasValue(1);
  }

  @Test
  void refusesMarketDataUntilTheRuntimeIsReady() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    PublicMarketHttpHandler handler = new PublicMarketHttpHandler(catalog(reads),
        Clock.fixed(NOW, ZoneOffset.UTC), () -> false);

    FakeExchange markets = request("GET", "/api/v1/public/markets");
    handler.handle(markets);
    FakeExchange snapshot = request(
        "GET", "/api/v1/public/markets/diamond-usd/snapshot?period=24h");
    handler.handle(snapshot);

    assertThat(markets.status).isEqualTo(503);
    assertThat(snapshot.status).isEqualTo(503);
    assertThat(markets.responseHeaders.getFirst("Cache-Control")).isEqualTo("no-store");
    assertThat(snapshot.responseHeaders.getFirst("Cache-Control")).isEqualTo("no-store");
    assertThat(reads).hasValue(0);
  }

  @Test
  void rejectsWritesUnknownMarketsAndUnsupportedPeriods() throws Exception {
    PublicMarketHttpHandler handler = new PublicMarketHttpHandler(catalog(new AtomicInteger()),
        Clock.fixed(NOW, ZoneOffset.UTC), () -> true);

    FakeExchange post = request("POST", "/api/v1/public/markets");
    handler.handle(post);
    assertThat(post.status).isEqualTo(405);
    assertThat(post.responseHeaders.getFirst("Allow")).isEqualTo("GET, HEAD");

    FakeExchange unknown = request("GET", "/api/v1/public/markets/unknown/snapshot?period=24h");
    handler.handle(unknown);
    assertThat(unknown.status).isEqualTo(404);

    FakeExchange period = request("GET", "/api/v1/public/markets/diamond-usd/snapshot?period=30d");
    handler.handle(period);
    assertThat(period.status).isEqualTo(400);

    FakeExchange extra = request("GET", "/api/v1/public/markets/diamond-usd/snapshot?period=24h&account=x");
    handler.handle(extra);
    assertThat(extra.status).isEqualTo(400);
  }

  @Test
  void supportsHeadWithoutWritingAResponseBody() throws Exception {
    PublicMarketHttpHandler handler = new PublicMarketHttpHandler(catalog(new AtomicInteger()),
        Clock.fixed(NOW, ZoneOffset.UTC), () -> true);

    FakeExchange head = request("HEAD", "/api/v1/public/markets");
    handler.handle(head);

    assertThat(head.status).isEqualTo(200);
    assertThat(head.body()).isEmpty();
  }

  private static PublicMarketCatalog catalog(AtomicInteger reads) {
    return new PublicMarketCatalog(Map.of("diamond-usd", "钻石"),
        (marketId, period, toExclusive) -> {
          reads.incrementAndGet();
          assertThat(period).isIn(MarketChartPeriod.values());
          return CompletableFuture.completedFuture(PublicMarketCatalogTest.snapshot(marketId, NOW));
        });
  }

  private static FakeExchange request(String method, String uri) {
    return new FakeExchange(method, URI.create(uri));
  }

  private static final class FakeExchange extends HttpExchange {
    private final String method;
    private final URI uri;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream response = new ByteArrayOutputStream();
    private int status;

    private FakeExchange(String method, URI uri) {
      this.method = method;
      this.uri = uri;
    }

    String body() { return response.toString(java.nio.charset.StandardCharsets.UTF_8); }
    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() { return uri; }
    @Override public String getRequestMethod() { return method; }
    @Override public HttpContext getHttpContext() { return null; }
    @Override public void close() {}
    @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
    @Override public OutputStream getResponseBody() { return response; }
    @Override public void sendResponseHeaders(int responseCode, long responseLength) { status = responseCode; }
    @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 50000); }
    @Override public int getResponseCode() { return status; }
    @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8765); }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public Object getAttribute(String name) { return null; }
    @Override public void setAttribute(String name, Object value) {}
    @Override public void setStreams(InputStream input, OutputStream output) {}
    @Override public HttpPrincipal getPrincipal() { return null; }
  }
}
