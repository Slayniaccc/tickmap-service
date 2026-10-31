package com.tickmap.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** Real local HTTP server, to prove the timeouts actually fire. */
class TickApiClientTimeoutTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TickApiClient clientFor(String baseUrl, Duration readTimeout) {
        var props = new UpstreamProperties(baseUrl, Duration.ofMillis(500), readTimeout,
                new UpstreamProperties.Retry(3, Duration.ofMillis(10), 2.0, Duration.ofMillis(50)));
        return new TickApiClient(UpstreamConfig.restClientBuilder(props).build(),
                new RetryExecutor(props.retry()), JsonMapper.builder().build());
    }

    @Test
    void slowResponseHitsReadTimeoutAndIsRetried() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/data/tick-sightings", exchange -> {
            try {
                if (hits.incrementAndGet() == 1) {
                    Thread.sleep(2_000); // much longer than the 300ms read timeout
                }
                byte[] body = "[{\"id\":\"a\"}]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (Exception ignored) {
                // client already gave up on the slow request
            }
        });
        server.start();

        long start = System.nanoTime();
        var records = clientFor("http://127.0.0.1:" + server.getAddress().getPort() + "/data", Duration.ofMillis(300))
                .fetchAllSightings();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(records).hasSize(1);
        assertThat(hits).hasValue(2);
        assertThat(elapsedMs).as("should not wait for the slow response").isLessThan(1_500);
    }

    @Test
    void refusedConnectionIsRetriedThenReportedAsUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        assertThatThrownBy(() -> clientFor("http://127.0.0.1:" + closedPort + "/data", Duration.ofSeconds(1)).fetchAllSightings())
                .isInstanceOf(UpstreamApiException.class)
                .hasMessageContaining("failed after 3 attempt(s)")
                .hasMessageContaining("unreachable")
                .satisfies(e -> assertThat(((UpstreamApiException) e).getHttpStatus()).isNull());
    }
}
