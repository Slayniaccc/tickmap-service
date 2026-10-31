package com.tickmap.upstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

class UpstreamClientTest {

    private static final String BASE = "http://upstream.test/data";
    private static final String SIGHTINGS = BASE + "/tick-sightings";
    private static final String STATS = BASE + "/stats";

    private MockRestServiceServer server;
    private UpstreamClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        var retryConfig = new UpstreamProperties.Retry(4, Duration.ofMillis(100), 2.0, Duration.ofSeconds(10));
        client = new UpstreamClient(builder.build(), new RetryExecutor(retryConfig, sleeps::add, () -> 1.0),
                JsonMapper.builder().build());
    }

    private static String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void returnsEveryRecordRawEvenWhenSomeAreMalformed() {
        server.expect(once(), requestTo(SIGHTINGS)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("""
                        [{'id':'a','date':'2022-08-01T06:40:31','location':'Manchester','species':'Marsh tick','latinName':'Ixodes apronophorus'},
                         {'id':'b','date':12345,'location':null},
                         'not even an object']"""), MediaType.APPLICATION_JSON));

        var records = client.fetchAllSightings();

        assertThat(records).hasSize(3);
        assertThat(records.get(0).get("location").asString()).isEqualTo("Manchester");
        assertThat(records.get(1).get("date").isNumber()).isTrue();
        assertThat(records.get(2).isString()).isTrue();
        server.verify();
    }

    @Test
    void emptyArrayIsAValidAnswer() {
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.fetchAllSightings()).isEmpty();
    }

    @Test
    void retriesRateLimitAndServerErrorsThenSucceeds() {
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body(json("{'error':'Rate limit exceeded, please try again later'}")));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(json("{'error':'Internal server error'}")));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess(json("[{'id':'a'}]"), MediaType.APPLICATION_JSON));

        assertThat(client.fetchAllSightings()).hasSize(1);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
        server.verify();
    }

    @Test
    void waitsAtLeastRetryAfterOn429() {
        server.expect(once(), requestTo(SIGHTINGS))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "3"));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.fetchAllSightings();

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void ignoresUnparseableRetryAfter() {
        server.expect(once(), requestTo(SIGHTINGS))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT"));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.fetchAllSightings();

        assertThat(sleeps).containsExactly(Duration.ofMillis(100));
    }

    @Test
    void givesUpAfterMaxAttemptsWhenUpstreamStaysDown() {
        server.expect(times(4), requestTo(SIGHTINGS)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(client::fetchAllSightings)
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("failed after 4 attempt(s)")
                .satisfies(e -> assertThat(((UpstreamException) e).getHttpStatus()).isEqualTo(503));
        server.verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        server.expect(once(), requestTo(SIGHTINGS))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).body(json("{'error':'Not found'}")));

        assertThatThrownBy(client::fetchAllSightings)
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("Not found");
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void retriesTruncatedJson() {
        server.expect(once(), requestTo(SIGHTINGS))
                .andRespond(withSuccess("[{\"id\":\"a\",\"date\":\"2022-", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[{\"id\":\"a\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.fetchAllSightings()).hasSize(1);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void retriesEmptyBody() {
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.fetchAllSightings()).isEmpty();
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void wrongShapeIsNotRetried() {
        server.expect(once(), requestTo(SIGHTINGS))
                .andRespond(withSuccess(json("{'error':'something odd'}"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::fetchAllSightings)
                .isInstanceOf(UpstreamException.class)
                .hasMessageContaining("Expected a JSON array");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesNetworkTimeouts() {
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        server.expect(once(), requestTo(SIGHTINGS)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.fetchAllSightings()).isEmpty();
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void readsReportedTotalFromStats() {
        server.expect(once(), requestTo(STATS))
                .andRespond(withSuccess(json("{'api_version':'1.0.0','total_sightings':1000}"), MediaType.APPLICATION_JSON));
        assertThat(client.fetchReportedTotal()).contains(1000);
    }

    @Test
    void statsFailureIsSwallowedBecauseItIsOnlyACrossCheck() {
        server.expect(times(4), requestTo(STATS)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(client.fetchReportedTotal()).isEmpty();
        server.verify();
    }

    @Test
    void statsWithMissingOrNonNumericTotalIsEmpty() {
        server.expect(once(), requestTo(STATS)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.fetchReportedTotal()).isEmpty();

        server.reset();
        server.expect(once(), requestTo(STATS))
                .andRespond(withSuccess(json("{'total_sightings':'lots'}"), MediaType.APPLICATION_JSON));
        assertThat(client.fetchReportedTotal()).isEmpty();
    }
}
