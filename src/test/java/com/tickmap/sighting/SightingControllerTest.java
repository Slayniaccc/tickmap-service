package com.tickmap.sighting;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tickmap.TestFixtures;

import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SightingControllerTest {

    private static final List<JsonNode> RAW = TestFixtures.records(TestFixtures.SNAPSHOT);

    @Autowired MockMvc mvc;
    @Autowired SightingRepository sightings;
    @Autowired SightingNormalizer normalizer;

    // Seed the real snapshot once; each test only removes what it added.
    @BeforeEach
    void seed() {
        sightings.deleteAll(sightings.findAll().stream().filter(s -> s.getSource() == Sighting.Source.USER).toList());
        if (sightings.count() == 0) {
            sightings.saveAll(RAW.stream().map(n -> {
                var a = (NormalizationResult.Accepted) normalizer.normalize(n);
                Sighting s = new Sighting(a.sighting().sourceId(), Sighting.Source.UPSTREAM);
                s.applyContent(a.sighting());
                return s;
            }).toList());
        }
    }

    // Independent oracle: counts straight from the raw JSON.
    private static long rawCount(Predicate<JsonNode> p) {
        return RAW.stream().filter(p).count();
    }

    private static String f(JsonNode n, String field) {
        return n.get(field).asString();
    }

    private ResultActions search(String... params) throws Exception {
        var req = get("/api/sightings");
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req);
    }

    @Nested
    class Search {

        @Test
        void defaultsToNewestFirstPageOfTwenty() throws Exception {
            search().andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1000))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.content.length()").value(20))
                    .andExpect(jsonPath("$.content[0].sightedAt").value("2024-12-30T04:32:12"))
                    .andExpect(jsonPath("$.content[0].region").value(org.hamcrest.Matchers.notNullValue()))
                    .andExpect(jsonPath("$.content[0].latitude").isNumber());
        }

        @Test
        void filtersByCityCaseInsensitively() throws Exception {
            long expected = rawCount(n -> f(n, "location").equals("London"));
            search("city", "london").andExpect(jsonPath("$.totalElements").value(expected));
            search("city", " LONDON ").andExpect(jsonPath("$.totalElements").value(expected));
        }

        @Test
        void resolvesCityAliases() throws Exception {
            search("city", "Newcastle upon Tyne")
                    .andExpect(jsonPath("$.totalElements").value(rawCount(n -> f(n, "location").equals("Newcastle"))));
        }

        @Test
        void filtersByRegion() throws Exception {
            search("region", "scotland").andExpect(jsonPath("$.totalElements")
                    .value(rawCount(n -> Set.of("Glasgow", "Edinburgh").contains(f(n, "location")))));
        }

        @Test
        void filtersBySpecies() throws Exception {
            search("species", "fox/BADGER tick")
                    .andExpect(jsonPath("$.totalElements").value(rawCount(n -> f(n, "species").equals("Fox/badger tick"))));
        }

        @Test
        void filtersByDateRangeWithInclusiveDateOnlyEnd() throws Exception {
            search("from", "2020-01-01", "to", "2020-12-31")
                    .andExpect(jsonPath("$.totalElements").value(rawCount(n -> f(n, "date").startsWith("2020"))));
        }

        @Test
        void exactDateTimeBoundsIncludeTheRecordAtThatInstant() throws Exception {
            JsonNode one = RAW.get(0);
            search("from", f(one, "date"), "to", f(one, "date"))
                    .andExpect(jsonPath("$.content[*].sourceId", hasItem(f(one, "id"))));
        }

        @Test
        void combinesAllFilters() throws Exception {
            long expected = rawCount(n -> f(n, "location").equals("Manchester")
                    && f(n, "species").equals("Marsh tick")
                    && f(n, "date").compareTo("2016-01-01") >= 0 && f(n, "date").compareTo("2021-01-01") < 0);
            search("city", "Manchester", "species", "Marsh tick", "from", "2016-01-01", "to", "2020-12-31")
                    .andExpect(jsonPath("$.totalElements").value(expected));
        }

        @Test
        void unknownCityIsAnEmptyPageNotAnError() throws Exception {
            search("city", "Atlantis").andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0))
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        void paginates() throws Exception {
            search("page", "1", "size", "50")
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.content.length()").value(50))
                    .andExpect(jsonPath("$.totalPages").value(20))
                    .andExpect(jsonPath("$.hasNext").value(true));
            search("page", "6", "size", "150")
                    .andExpect(jsonPath("$.content.length()").value(100))
                    .andExpect(jsonPath("$.hasNext").value(false));
        }

        @Test
        void capsPageSizeAt200() throws Exception {
            search("size", "100000").andExpect(jsonPath("$.size").value(200))
                    .andExpect(jsonPath("$.content.length()").value(200));
        }

        @Test
        void sortsAscending() throws Exception {
            search("sort", "sightedAt,asc").andExpect(jsonPath("$.content[0].sightedAt").value("2012-01-03T20:40:43"));
        }

        @Test
        void rejectsUnknownSortField() throws Exception {
            search("sort", "fingerprint").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("Cannot sort by 'fingerprint'")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"yesterday", "01/02/2020", "2020-13-01", "2020-01-01T25:00:00"})
        void rejectsBadDates(String bad) throws Exception {
            search("from", bad).andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", endsWith("problem+json")))
                    .andExpect(jsonPath("$.detail").value(containsString("'from' must be an ISO date")));
        }

        @Test
        void rejectsFromAfterTo() throws Exception {
            search("from", "2021-01-01", "to", "2020-01-01").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("'from' must be before 'to'"));
        }

        @Test
        void getsOneByIdOr404() throws Exception {
            Long id = sightings.findAll().getFirst().getId();
            mvc.perform(get("/api/sightings/{id}", id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
            mvc.perform(get("/api/sightings/{id}", 99_999_999)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("No sighting with id 99999999"));
        }
    }

    @Nested
    class Create {

        private ResultActions submit(String json) throws Exception {
            return mvc.perform(post("/api/sightings").contentType(MediaType.APPLICATION_JSON).content(json.replace('\'', '"')));
        }

        @Test
        void createsANormalisedUserSighting() throws Exception {
            String location = submit("{'date':'2025-06-14T17:30:00','location':' leeds ','species':'marsh tick'}")
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", matchesPattern("/api/sightings/\\d+")))
                    .andExpect(jsonPath("$.city").value("Leeds"))
                    .andExpect(jsonPath("$.region").value("Yorkshire and the Humber"))
                    .andExpect(jsonPath("$.species").value("Marsh tick"))
                    .andExpect(jsonPath("$.latinName").value("Ixodes apronophorus"))
                    .andExpect(jsonPath("$.source").value("USER"))
                    .andExpect(jsonPath("$.sourceId").value(org.hamcrest.Matchers.nullValue()))
                    .andReturn().getResponse().getHeader("Location");

            mvc.perform(get(location)).andExpect(status().isOk());
            search("city", "Leeds", "from", "2025-06-14", "to", "2025-06-14")
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void acceptsUnknownCityWithUnknownRegion() throws Exception {
            submit("{'date':'2025-06-14T17:30:00','location':'York','latinName':'Ixodes canisuga'}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.region").value("Unknown"))
                    .andExpect(jsonPath("$.species").value("Fox/badger tick"));
        }

        @Test
        void duplicateSubmissionIs409WithExistingId() throws Exception {
            String body = "{'date':'2025-06-14T17:30:00','location':'Leeds','species':'Marsh tick'}";
            submit(body).andExpect(status().isCreated());
            submit(body).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Duplicate sighting"))
                    .andExpect(jsonPath("$.existingId").isNumber());
        }

        @Test
        void resubmittingAnUpstreamSightingIs409() throws Exception {
            JsonNode up = RAW.get(0);
            submit("{'date':'" + f(up, "date") + "','location':'" + f(up, "location").toUpperCase()
                    + "','species':'" + f(up, "species") + "'}")
                    .andExpect(status().isConflict());
        }

        @Test
        void contradictorySpeciesIs400WithReason() throws Exception {
            submit("{'date':'2025-06-14T17:30:00','location':'Leeds','species':'Marsh tick','latinName':'Ixodes canisuga'}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("SPECIES_LATIN_MISMATCH"));
        }

        @Test
        void missingSpeciesIs400WithReason() throws Exception {
            submit("{'date':'2025-06-14T17:30:00','location':'Leeds'}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("MISSING_SPECIES"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "{'location':'Leeds','species':'Marsh tick'}",                                  // no date
                "{'date':'2999-01-01T00:00:00','location':'Leeds','species':'Marsh tick'}",     // future
                "{'date':'2025-06-14T17:30:00','location':'  ','species':'Marsh tick'}",        // blank location
                "{'date':'14/06/2025','location':'Leeds','species':'Marsh tick'}",              // bad date format
                "{'date':'2025-06-14T17:30:00','location':'Leeds','species':'Marsh tick'",      // malformed JSON
                "[]"
        })
        void invalidRequestsAre400ProblemDetails(String body) throws Exception {
            submit(body).andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", endsWith("problem+json")));
        }
    }
}
