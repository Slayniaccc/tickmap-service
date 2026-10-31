package com.tickmap.upstream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Returns raw JsonNodes rather than typed objects, so one malformed record can't fail the
 * whole batch. Validation happens per record afterwards.
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final RestClient rest;
    private final RetryExecutor retry;
    private final JsonMapper json;

    public UpstreamClient(RestClient tickApiRestClient, RetryExecutor retry, JsonMapper json) {
        this.rest = tickApiRestClient;
        this.retry = retry;
        this.json = json;
    }

    // Upstream has no pagination, so this is a single call.
    public List<JsonNode> fetchAllSightings() {
        return retry.execute("GET /tick-sightings", () -> {
            JsonNode root = parse(get("/tick-sightings"));
            if (!root.isArray()) {
                throw UpstreamException.unexpectedShape(
                        "Expected a JSON array from /tick-sightings but got " + root.getNodeType());
            }
            List<JsonNode> records = new ArrayList<>(root.size());
            root.forEach(records::add);
            return records;
        });
    }

    // Best effort: only used to cross-check the sync.
    public Optional<Integer> fetchReportedTotal() {
        try {
            return retry.execute("GET /stats", () -> {
                JsonNode total = parse(get("/stats")).get("total_sightings");
                return Optional.ofNullable(total != null && total.isInt() ? total.intValue() : null);
            });
        } catch (UpstreamException e) {
            log.warn("Could not read upstream /stats: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String get(String path) {
        try {
            return rest.get()
                    .uri(path)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw UpstreamException.forStatus(
                                response.getStatusCode().value(), readBody(response), retryAfter(response));
                    })
                    .body(String.class);
        } catch (ResourceAccessException e) {
            // timeouts, DNS failures, refused connections
            throw UpstreamException.network(e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            throw UpstreamException.corruptBody(new IllegalStateException("empty response body"));
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            throw UpstreamException.corruptBody(e);
        }
    }

    private static String readBody(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(2048), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // Seconds form only; the HTTP-date form is ignored.
    static Duration retryAfter(ClientHttpResponse response) {
        String value = response.getHeaders().getFirst("Retry-After");
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
