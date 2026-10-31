package com.tickmap.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.tickmap.TestFixtures;
import com.tickmap.sighting.NormalizationResult;
import com.tickmap.sighting.Sighting;
import com.tickmap.sighting.SightingNormalizer;
import com.tickmap.sighting.SightingRepository;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatsControllerTest {

    private static final List<JsonNode> RAW = TestFixtures.records(TestFixtures.SNAPSHOT);

    @Autowired MockMvc mvc;
    @Autowired SightingRepository sightings;
    @Autowired SightingNormalizer normalizer;

    @BeforeEach
    void seed() {
        TestFixtures.seedSnapshot(normalizer, sightings);
    }

    private JsonNode getJson(String url) throws Exception {
        String body = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return TestFixtures.JSON.readTree(body);
    }

    private static Map<String, Long> rawCountBy(Function<JsonNode, String> key) {
        return RAW.stream().collect(Collectors.groupingBy(key, Collectors.counting()));
    }

    private void addUserSighting(LocalDateTime at, String city) {
        var a = (NormalizationResult.Accepted) normalizer.normalize(null, at, city, "Marsh tick", null);
        Sighting s = new Sighting(null, Sighting.Source.USER);
        s.applyContent(a.sighting());
        sightings.save(s);
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    @Test
    void summaryCoversTheWholeDataset() throws Exception {
        mvc.perform(get("/api/stats/summary"))
                .andExpect(jsonPath("$.totalSightings").value(1000))
                .andExpect(jsonPath("$.earliest").value("2012-01-03T20:40:43"))
                .andExpect(jsonPath("$.latest").value("2024-12-30T04:32:12"))
                .andExpect(jsonPath("$.cities").value(14))
                .andExpect(jsonPath("$.regions").value(10))
                .andExpect(jsonPath("$.species").value(5));
    }

    @Test
    void regionCountsMatchRawDataAndAreSortedDescending() throws Exception {
        List<JsonNode> regions = list(getJson("/api/stats/regions"));

        assertThat(regions.stream().mapToLong(r -> r.get("count").asLong()).sum()).isEqualTo(1000);
        assertThat(regions).isSortedAccordingTo((a, b) -> Long.compare(b.get("count").asLong(), a.get("count").asLong()));
        long scotland = rawCountBy(n -> n.get("location").asString()).entrySet().stream()
                .filter(e -> e.getKey().equals("Glasgow") || e.getKey().equals("Edinburgh"))
                .mapToLong(Map.Entry::getValue).sum();
        assertThat(regions).anySatisfy(r -> {
            assertThat(r.get("region").asString()).isEqualTo("Scotland");
            assertThat(r.get("count").asLong()).isEqualTo(scotland);
        });
    }

    @Test
    void cityCountsIncludeCoordinatesForTheMap() throws Exception {
        List<JsonNode> cities = list(getJson("/api/stats/cities"));
        Map<String, Long> expected = rawCountBy(n -> n.get("location").asString());

        assertThat(cities).hasSize(14).allSatisfy(c -> {
            assertThat(c.get("count").asLong()).isEqualTo(expected.get(c.get("city").asString()));
            assertThat(c.get("latitude").isNumber()).isTrue();
            assertThat(c.get("longitude").isNumber()).isTrue();
        });
        assertThat(cities.getFirst().get("city").asString()).isEqualTo("Nottingham");
    }

    @Test
    void speciesCountsMatchRawData() throws Exception {
        Map<String, Long> expected = rawCountBy(n -> n.get("species").asString());
        assertThat(list(getJson("/api/stats/species"))).hasSize(5).allSatisfy(s ->
                assertThat(s.get("count").asLong()).isEqualTo(expected.get(s.get("species").asString())));
    }

    @Test
    void statsHonourFilters() throws Exception {
        long londonMarsh = RAW.stream().filter(n -> n.get("location").asString().equals("London")
                && n.get("species").asString().equals("Marsh tick")).count();
        mvc.perform(get("/api/stats/regions").param("city", "london").param("species", "Marsh tick"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(londonMarsh));
    }

    @Test
    void monthlyTrendIsContiguousAndMatchesRawCounts() throws Exception {
        JsonNode trend = getJson("/api/stats/trends?interval=month");
        List<JsonNode> buckets = list(trend.get("buckets"));
        Map<String, Long> byMonth = rawCountBy(n -> n.get("date").asString().substring(0, 7));

        assertThat(trend.get("interval").asString()).isEqualTo("MONTH");
        assertThat(buckets).hasSize(13 * 12);
        assertThat(buckets.getFirst().get("periodStart").asString()).isEqualTo("2012-01-01");
        assertThat(buckets.getLast().get("periodStart").asString()).isEqualTo("2024-12-01");
        assertThat(buckets).allSatisfy(b -> assertThat(b.get("count").asLong())
                .isEqualTo(byMonth.getOrDefault(b.get("periodStart").asString().substring(0, 7), 0L)));
    }

    @Test
    void weeklyTrendUsesIsoWeeksStartingMonday() throws Exception {
        List<JsonNode> buckets = list(getJson("/api/stats/trends?interval=week").get("buckets"));

        assertThat(buckets.stream().mapToLong(b -> b.get("count").asLong()).sum()).isEqualTo(1000);
        for (int i = 0; i < buckets.size(); i++) {
            LocalDate start = LocalDate.parse(buckets.get(i).get("periodStart").asString());
            assertThat(start.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            if (i > 0) {
                assertThat(start).isEqualTo(LocalDate.parse(buckets.get(i - 1).get("periodStart").asString()).plusWeeks(1));
            }
        }
        // Independent oracle: the JDK's ISO week-of-year fields.
        Map<String, Long> byIsoWeek = rawCountBy(n -> {
            LocalDate d = LocalDate.parse(n.get("date").asString().substring(0, 10));
            return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        });
        assertThat(buckets.stream().map(b -> b.get("count").asLong()).filter(c -> c > 0).sorted().toList())
                .isEqualTo(byIsoWeek.values().stream().sorted().toList());
    }

    @Test
    void weekSpanningNewYearIsOneBucketAndGapsAreZeroFilled() throws Exception {
        addUserSighting(LocalDateTime.of(2025, 12, 29, 9, 0), "Leeds");  // Monday
        addUserSighting(LocalDateTime.of(2026, 1, 4, 9, 0), "Leeds");    // Sunday, same ISO week
        addUserSighting(LocalDateTime.of(2026, 1, 5, 9, 0), "Leeds");    // next Monday

        List<JsonNode> buckets = list(getJson("/api/stats/trends?interval=week&from=2025-12-20&to=2026-01-20").get("buckets"));

        assertThat(buckets).extracting(b -> b.get("periodStart").asString() + "=" + b.get("count").asLong())
                .containsExactly("2025-12-15=0", "2025-12-22=0", "2025-12-29=2", "2026-01-05=1", "2026-01-12=0", "2026-01-19=0");
    }

    @Test
    void trendWithNoMatchesIsEmptyUnlessBoundsAreGiven() throws Exception {
        assertThat(list(getJson("/api/stats/trends?city=Atlantis").get("buckets"))).isEmpty();
        assertThat(list(getJson("/api/stats/trends?city=Atlantis&from=2020-01-01&to=2020-03-31").get("buckets")))
                .extracting(b -> b.get("count").asLong()).containsExactly(0L, 0L, 0L);
    }

    @Test
    void acceptsIntervalSynonymsCaseInsensitively() throws Exception {
        mvc.perform(get("/api/stats/trends").param("interval", "WEEKLY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interval").value("WEEK"));
    }

    @Test
    void rejectsUnknownInterval() throws Exception {
        mvc.perform(get("/api/stats/trends").param("interval", "fortnight"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("interval must be 'week' or 'month'")));
    }

    @Test
    void rejectsRangesWithTooManyBuckets() throws Exception {
        mvc.perform(get("/api/stats/trends").param("interval", "week").param("from", "1900-01-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Range too large")));
    }

    @Test
    void rejectsBadFilterValuesOnStatsToo() throws Exception {
        mvc.perform(get("/api/stats/regions").param("to", "not-a-date")).andExpect(status().isBadRequest());
    }
}
