package com.tickmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tickmap.ingestion.IngestionRunRepository;
import com.tickmap.ingestion.RejectedRecordRepository;
import com.tickmap.sighting.SightingRepository;

import tools.jackson.databind.JsonNode;

/**
 * Whole application, no mocks: a real HTTP server stands in for the Elanco API and scripts
 * failures and messy data. Everything is checked through our own HTTP endpoints.
 */
@SpringBootTest(properties = {
        "tickmap.upstream.read-timeout=300ms",
        "tickmap.upstream.retry.initial-backoff=10ms",
        "tickmap.upstream.retry.max-backoff=50ms"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndToEndIngestionTest {

    private record Reply(int status, String body, String retryAfter, long delayMs) {
        static Reply ok(String body) { return new Reply(200, body, null, 0); }
        static Reply error(int status) { return new Reply(status, "{\"error\":\"nope\"}", null, 0); }
    }

    private static final String MESSY = read("/fixtures/messy-sightings.json");
    private static final HttpServer UPSTREAM;
    private static final Deque<Reply> script = new ArrayDeque<>();
    private static volatile Reply fallback = Reply.error(503);
    private static final AtomicInteger sightingHits = new AtomicInteger();

    static {
        try {
            UPSTREAM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        UPSTREAM.setExecutor(Executors.newCachedThreadPool());
        UPSTREAM.createContext("/data/tick-sightings", ex -> {
            sightingHits.incrementAndGet();
            Reply r;
            synchronized (script) {
                r = script.isEmpty() ? fallback : script.poll();
            }
            respond(ex, r);
        });
        UPSTREAM.createContext("/data/stats", ex -> respond(ex, Reply.ok("{\"total_sightings\":25}")));
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("tickmap.upstream.base-url", () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort() + "/data");
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired SightingRepository sightings;
    @Autowired IngestionRunRepository runs;
    @Autowired RejectedRecordRepository rejections;

    @BeforeEach
    void reset() {
        rejections.deleteAll();
        sightings.deleteAll();
        runs.deleteAll();
        synchronized (script) {
            script.clear();
        }
        fallback = Reply.error(503);
        sightingHits.set(0);
    }

    private static void upstreamWill(Reply... replies) {
        synchronized (script) {
            script.addAll(List.of(replies));
        }
    }

    private JsonNode call(RequestBuilder req, int expectedStatus) throws Exception {
        String body = mvc.perform(req).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return TestFixtures.JSON.readTree(body);
    }

    private JsonNode sync() throws Exception {
        return call(post("/api/ingestion/runs"), 201);
    }

    @Test
    void survivesFlakyUpstreamMessyDataOutagesAndRecovery() throws Exception {
        // 1. Rate limited, then a server error, then messy data: retried through to success.
        upstreamWill(new Reply(429, "{\"error\":\"Rate limit exceeded\"}", "0", 0), Reply.error(500), Reply.ok(MESSY));
        JsonNode first = sync();
        assertThat(first.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(sightingHits).hasValue(3);
        assertThat(first.get("inserted").asInt()).isEqualTo(10);
        assertThat(first.get("rejected").asInt()).isEqualTo(12);
        assertThat(first.get("duplicates").asInt()).isEqualTo(3);
        assertThat(first.get("upstreamReportedTotal").asInt()).isEqualTo(25);

        assertThat(call(get("/api/sightings").param("city", "manchester"), 200).get("totalElements").asInt()).isEqualTo(1);
        long regionTotal = 0;
        for (JsonNode r : call(get("/api/stats/regions"), 200)) {
            regionTotal += r.get("count").asLong();
        }
        assertThat(regionTotal).isEqualTo(10);
        assertThat(call(get("/api/ingestion/runs/{id}/rejected", first.get("id").asLong()).param("size", "100"), 200)
                .get("totalElements").asInt()).isEqualTo(15);
        String lastGoodSync = call(get("/api/ingestion/status"), 200).get("lastSuccessfulSync").asString();

        // 2. Total outage: every retry used, run fails, data and lastSuccessfulSync untouched.
        sightingHits.set(0);
        JsonNode outage = sync();
        assertThat(outage.get("status").asString()).isEqualTo("FAILED");
        assertThat(outage.get("error").asString()).contains("failed after 4 attempt(s)").contains("503");
        assertThat(sightingHits).hasValue(4);
        JsonNode status = call(get("/api/ingestion/status"), 200);
        assertThat(status.get("lastSuccessfulSync").asString()).isEqualTo(lastGoodSync);
        assertThat(status.get("lastRun").get("status").asString()).isEqualTo("FAILED");
        assertThat(status.get("totalSightings").asInt()).isEqualTo(10);
        assertThat(call(get("/api/sightings"), 200).get("totalElements").asInt()).isEqualTo(10);

        // 3. Wrong response shape: fails fast without burning retries.
        sightingHits.set(0);
        upstreamWill(Reply.ok("{\"error\":\"maintenance\"}"));
        assertThat(sync().get("status").asString()).isEqualTo("FAILED");
        assertThat(sightingHits).hasValue(1);

        // 4. Recovery with a corrected record: the existing row is updated, nothing duplicated.
        // Only the first m1 (the one followed by m2) moves; its later duplicate copy stays as it was.
        String m1Then = "\"2022-08-01T06:40:31\", \"location\": \"London\",      \"species\": \"Marsh tick\",      \"latinName\": \"Ixodes apronophorus\"},\n  {\"id\": \"m2\"";
        String m1Now = m1Then.replace("06:40:31", "07:00:00");
        upstreamWill(Reply.ok(MESSY.replace(m1Then, m1Now)));
        JsonNode recovered = sync();
        assertThat(recovered.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(recovered.get("updated").asInt()).isEqualTo(1);
        assertThat(recovered.get("inserted").asInt()).isZero();
        assertThat(call(get("/api/sightings").param("city", "London").param("from", "2022-08-01T07:00:00")
                .param("to", "2022-08-01T07:00:00"), 200).get("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get("/api/ingestion/status"), 200).get("stale").asBoolean()).isFalse();
    }

    @Test
    void hangingUpstreamTimesOutInsteadOfBlockingAndKeepsData() throws Exception {
        upstreamWill(Reply.ok(MESSY));
        sync();

        fallback = new Reply(200, "[]", null, 2_000);  // never answers within the 300ms read timeout
        long start = System.nanoTime();
        JsonNode run = sync();
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        assertThat(run.get("error").asString()).contains("unreachable");
        assertThat(ms).as("4 attempts x 300ms plus small backoffs").isLessThan(2_500);
        assertThat(call(get("/api/sightings"), 200).get("totalElements").asInt()).isEqualTo(10);
    }

    private static void respond(HttpExchange ex, Reply r) throws IOException {
        try {
            if (r.delayMs() > 0) {
                Thread.sleep(r.delayMs());
            }
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (r.retryAfter() != null) {
                ex.getResponseHeaders().add("Retry-After", r.retryAfter());
            }
            ex.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException | IOException ignored) {
            // client timed out and closed the connection
        }
    }

    private static String read(String resource) {
        try (InputStream in = EndToEndIngestionTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
