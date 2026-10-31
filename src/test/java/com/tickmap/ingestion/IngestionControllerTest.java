package com.tickmap.ingestion;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tickmap.TestFixtures;
import com.tickmap.sighting.SightingRepository;
import com.tickmap.upstream.TickApiClient;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestionControllerTest {

    @MockitoBean
    TickApiClient api;

    @Autowired MockMvc mvc;
    @Autowired SightingRepository sightings;
    @Autowired IngestionRunRepository runs;
    @Autowired RejectedRecordRepository rejections;
    @Autowired IngestionService ingestion;

    @BeforeEach
    void reset() {
        rejections.deleteAll();
        sightings.deleteAll();
        runs.deleteAll();
        when(api.fetchReportedTotal()).thenReturn(Optional.empty());
        when(api.fetchAllSightings()).thenReturn(TestFixtures.records(TestFixtures.MESSY));
    }

    private long triggerRun() throws Exception {
        String body = mvc.perform(post("/api/ingestion/runs")).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body).get("id").asLong();
    }

    @Test
    void statusBeforeAnySync() throws Exception {
        mvc.perform(get("/api/ingestion/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSuccessfulSync").value(nullValue()))
                .andExpect(jsonPath("$.stale").value(true))
                .andExpect(jsonPath("$.syncInProgress").value(false))
                .andExpect(jsonPath("$.totalSightings").value(0))
                .andExpect(jsonPath("$.lastRun").value(nullValue()));
    }

    @Test
    void manualTriggerRunsASyncAndStatusReflectsIt() throws Exception {
        mvc.perform(post("/api/ingestion/runs"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/ingestion/runs/\\d+")))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.fetched").value(25))
                .andExpect(jsonPath("$.inserted").value(10))
                .andExpect(jsonPath("$.rejected").value(12))
                .andExpect(jsonPath("$.duplicates").value(3));

        mvc.perform(get("/api/ingestion/status"))
                .andExpect(jsonPath("$.lastSuccessfulSync").value(notNullValue()))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.totalSightings").value(10))
                .andExpect(jsonPath("$.lastRun.status").value("SUCCESS"));

        mvc.perform(get("/api/ingestion/runs"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void failedSyncIsRecordedAndStatusStillShowsLastGoodSync() throws Exception {
        triggerRun();
        when(api.fetchAllSightings()).thenThrow(IngestionServiceTest.upstreamDown());

        mvc.perform(post("/api/ingestion/runs"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("503")));

        mvc.perform(get("/api/ingestion/status"))
                .andExpect(jsonPath("$.lastSuccessfulSync").value(notNullValue()))
                .andExpect(jsonPath("$.lastRun.status").value("FAILED"))
                .andExpect(jsonPath("$.totalSightings").value(10));
    }

    @Test
    void rejectedRecordsArePagedWithReasonsAndRawJson() throws Exception {
        long runId = triggerRun();

        mvc.perform(get("/api/ingestion/runs/{id}/rejected", runId).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.totalElements").value(15))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.content[0].reason").value(notNullValue()))
                .andExpect(jsonPath("$.content[0].rawPayload").value(notNullValue()));

        mvc.perform(get("/api/ingestion/runs/{id}/rejected", runId).param("page", "2").param("size", "5"))
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void unknownRunIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/ingestion/runs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", endsWith("problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("No ingestion run with id 999999"));

        mvc.perform(get("/api/ingestion/runs/999999/rejected"))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerWhileASyncIsRunningIs409() throws Exception {
        CountDownLatch inFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.fetchAllSightings()).thenAnswer(inv -> {
            inFetch.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        Thread background = new Thread(() -> ingestion.runSync(IngestionRun.Trigger.SCHEDULED));
        background.start();
        inFetch.await(5, TimeUnit.SECONDS);
        try {
            mvc.perform(post("/api/ingestion/runs"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A sync is already in progress"));
        } finally {
            release.countDown();
            background.join(5_000);
        }
    }
}
