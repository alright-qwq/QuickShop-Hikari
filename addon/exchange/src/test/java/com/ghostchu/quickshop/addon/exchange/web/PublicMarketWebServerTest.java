package com.ghostchu.quickshop.addon.exchange.web;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicMarketWebServerTest {
  @Test
  void servesLoopbackRequestsAndReleasesThePortOnRepeatedClose() throws Exception {
    int port = freePort();
    PublicMarketWebConfig config = new PublicMarketWebConfig(true, "127.0.0.1", port,
        Duration.ofSeconds(3), 2, 4);
    PublicMarketCatalog catalog = new PublicMarketCatalog(Map.of("diamond-usd", "钻石"),
        (marketId, period, toExclusive) -> CompletableFuture.completedFuture(
            PublicMarketCatalogTest.snapshot(marketId, toExclusive)));
    PublicMarketWebServer server = new PublicMarketWebServer(config, catalog,
        Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC), () -> true);

    server.start();
    HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api/v1/public/markets")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("diamond-usd");

    server.close();
    server.close();
    try (ServerSocket rebound = new ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
      assertThat(rebound.isBound()).isTrue();
    }
  }

  @Test
  void retriesWorkerDrainAfterTheFirstCloseTimesOut() throws Exception {
    int port = freePort();
    CountDownLatch requestStarted = new CountDownLatch(1);
    CompletableFuture<com.ghostchu.quickshop.addon.exchange.display.MarketDisplaySnapshot> blocked =
        new CompletableFuture<>();
    PublicMarketWebConfig config = new PublicMarketWebConfig(true, "127.0.0.1", port,
        Duration.ofSeconds(3), 1, 1);
    PublicMarketCatalog catalog = new PublicMarketCatalog(Map.of("diamond-usd", "钻石"),
        (marketId, period, toExclusive) -> {
          requestStarted.countDown();
          return blocked;
        });
    PublicMarketWebServer server = new PublicMarketWebServer(config, catalog,
        Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC), () -> true,
        Duration.ofMillis(100));
    server.start();
    URI uri = URI.create("http://127.0.0.1:" + port
        + "/api/v1/public/markets/diamond-usd/snapshot?period=24h");
    CompletableFuture<HttpResponse<String>> response = HttpClient.newHttpClient().sendAsync(
        HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(requestStarted.await(2L, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(server::close)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("timed out");

    Thread retry = Thread.ofPlatform().start(server::close);
    Thread.sleep(50L);
    assertThat(retry.isAlive()).isTrue();
    blocked.complete(PublicMarketCatalogTest.snapshot(
        "diamond-usd", Instant.parse("2026-08-01T12:00:00Z")));
    retry.join(2_000L);

    assertThat(retry.isAlive()).isFalse();
    assertThat(response.isDone()).isTrue();
    server.close();
  }

  @Test
  void coalescesSnapshotReadsWithinTheConfiguredCacheWindow() throws Exception {
    int port = freePort();
    AtomicInteger reads = new AtomicInteger();
    PublicMarketWebConfig config = new PublicMarketWebConfig(true, "127.0.0.1", port,
        Duration.ofSeconds(30), 2, 4);
    PublicMarketCatalog catalog = new PublicMarketCatalog(Map.of("diamond-usd", "钻石"),
        (marketId, period, toExclusive) -> {
          reads.incrementAndGet();
          return CompletableFuture.completedFuture(PublicMarketCatalogTest.snapshot(marketId, toExclusive));
        });
    try (PublicMarketWebServer server = new PublicMarketWebServer(config, catalog,
        Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC), () -> true)) {
      server.start();
      HttpClient client = HttpClient.newHttpClient();
      URI uri = URI.create("http://127.0.0.1:" + port
          + "/api/v1/public/markets/diamond-usd/snapshot?period=24h");
      for (int count = 0; count < 3; count++) {
        assertThat(client.send(HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
      }
      assertThat(reads).hasValue(1);
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1,
        java.net.InetAddress.getByName("127.0.0.1"))) {
      return socket.getLocalPort();
    }
  }
}
