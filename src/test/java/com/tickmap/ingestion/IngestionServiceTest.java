package com.tickmap.ingestion;

import static com.tickmap.TestFixtures.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.tickmap.TestFixtures;
import com.tickmap.ingestion.IngestionService.RunSummary;
import com.tickmap.sighting.NormalizationResult;
import com.tickmap.sighting.Sighting;
import com.tickmap.sighting.SightingNormalizer;
import com.tickmap.sighting.SightingRepository;
import com.tickmap.upstream.TickApiClient;
import com.tickmap.upstream.UpstreamApiException;

import tools.jackson.databind.JsonNode;

// Small chunk size so the 1000-record snapshot crosses many chunk boundaries.
@SpringBootTest(properties = "tickmap.ingestion.chunk-size=7")
@ActiveProfiles("test")
class IngestionServiceTest {

    @MockitoBean
    TickApiClient api;

    @Autowired IngestionService ingestion;
    @Autowired SightingRepository sightings;
    @Autowired IngestionRunRepository runs;
    @Autowired RejectedRecordRepository rejections;
    @Autowired SightingNormalizer normalizer;

    @BeforeEach
    void reset() {
        rejections.deleteAll();
        sightings.deleteAll();
        runs.deleteAll();
        when(api.fetchReportedTotal()).thenReturn(Optional.empty());
    }

    private RunSummary sync(List<JsonNode> feed) {
        when(api.fetchAllSightings()).thenReturn(feed);
        return ingestion.runSync(IngestionRun.Trigger.MANUAL).orElseThrow();
    }

    static JsonNode record(String id, String date, String city, String species) {
        return node("{'id':'" + id + "','date':'" + date + "','location':'" + city + "','species':'" + species + "'}");
    }

    static UpstreamApiException upstreamDown() {
        return UpstreamApiException.exhausted("GET /tick-sightings", 4, UpstreamApiException.forStatus(503, "", null));
    }

    @Test
    void messyFeedEveryRecordIsStoredOrRecordedWithAReason() {
        RunSummary run = sync(TestFixtures.records(TestFixtures.MESSY));

        assertThat(run.status()).isEqualTo(IngestionRun.Status.SUCCESS);
        assertThat(run.fetched()).isEqualTo(25);
        assertThat(run.inserted()).isEqualTo(10);
        assertThat(run.rejected()).isEqualTo(12);
        assertThat(run.duplicates()).isEqualTo(3);
        assertThat(sightings.count()).isEqualTo(10);
        assertThat(rejections.countByRunId(run.id())).isEqualTo(15);

        assertThat(rejections.findAll()).anySatisfy(r -> {
            assertThat(r.getSourceId()).isEqualTo("m7");
            assertThat(r.getReason()).isEqualTo(RejectionReason.INVALID_DATE);
            assertThat(r.getRawPayload()).contains("not-a-date");
        });
        assertThat(rejections.countByReasonForRun(run.id()))
                .anySatisfy(row -> assertThat(row).containsExactly(RejectionReason.NOT_AN_OBJECT, 2L))
                .anySatisfy(row -> assertThat(row).containsExactly(RejectionReason.DUPLICATE_SOURCE_ID, 2L));
    }

    @Test
    void rerunningTheSameFeedChangesNothing() {
        sync(TestFixtures.records(TestFixtures.MESSY));
        RunSummary second = sync(TestFixtures.records(TestFixtures.MESSY));

        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isZero();
        // 9 kept records have ids and match themselves; the one without an id matches by content.
        assertThat(second.unchanged()).isEqualTo(9);
        assertThat(second.duplicates()).isEqualTo(4);
        assertThat(sightings.count()).isEqualTo(10);
    }

    @Test
    void fullRealSnapshotLoadsAcrossManyChunks() {
        when(api.fetchReportedTotal()).thenReturn(Optional.of(1000));
        RunSummary run = sync(TestFixtures.records(TestFixtures.SNAPSHOT));

        assertThat(run.inserted()).isEqualTo(1000);
        assertThat(run.rejected()).isZero();
        assertThat(run.duplicates()).isZero();
        assertThat(run.upstreamReportedTotal()).isEqualTo(1000);
        assertThat(sightings.count()).isEqualTo(1000);
    }

    @Test
    void upstreamCorrectionUpdatesTheExistingRow() {
        sync(List.of(record("a", "2022-01-01T10:00:00", "Leeds", "Marsh tick")));
        RunSummary run = sync(List.of(record("a", "2022-01-02T11:00:00", "Leeds", "Marsh tick")));

        assertThat(run.updated()).isEqualTo(1);
        assertThat(sightings.count()).isEqualTo(1);
        assertThat(sightings.findAll().getFirst().getSightedAt()).isEqualTo(LocalDateTime.of(2022, 1, 2, 11, 0));
    }

    @Test
    void updateThatWouldCollideWithAnotherRowIsFlaggedNotApplied() {
        sync(List.of(record("a", "2022-01-01T10:00:00", "Leeds", "Marsh tick"),
                record("b", "2022-05-05T10:00:00", "Leeds", "Marsh tick")));
        RunSummary run = sync(List.of(record("a", "2022-05-05T10:00:00", "Leeds", "Marsh tick")));

        assertThat(run.updated()).isZero();
        assertThat(run.duplicates()).isEqualTo(1);
        assertThat(sightings.findBySourceIdIn(List.of("a")).getFirst().getSightedAt())
                .isEqualTo(LocalDateTime.of(2022, 1, 1, 10, 0));
    }

    @Test
    void upstreamCopyOfAUserSubmittedSightingIsADuplicate() {
        var n = ((NormalizationResult.Accepted) normalizer.normalize(null, LocalDateTime.of(2023, 3, 3, 9, 0),
                "Bristol", "Marsh tick", null)).sighting();
        Sighting user = new Sighting(null, Sighting.Source.USER);
        user.applyContent(n);
        sightings.save(user);

        RunSummary run = sync(List.of(record("up-1", "2023-03-03T09:00:00", "bristol", "MARSH TICK")));

        assertThat(run.inserted()).isZero();
        assertThat(run.duplicates()).isEqualTo(1);
        assertThat(rejections.findAll()).singleElement()
                .satisfies(r -> assertThat(r.getDetail()).contains("user submitted"));
    }

    @Test
    void apiOutageFailsTheRunButKeepsServingLastGoodData() {
        RunSummary good = sync(TestFixtures.records(TestFixtures.SNAPSHOT));
        when(api.fetchAllSightings()).thenThrow(upstreamDown());

        RunSummary failed = ingestion.runSync(IngestionRun.Trigger.SCHEDULED).orElseThrow();

        assertThat(failed.status()).isEqualTo(IngestionRun.Status.FAILED);
        assertThat(failed.error()).contains("failed after 4 attempt(s)").contains("503");
        assertThat(sightings.count()).isEqualTo(1000);
        IngestionService.Status status = ingestion.status();
        assertThat(status.lastSuccessfulSync()).isEqualTo(runs.findById(good.id()).orElseThrow().getFinishedAt());
        assertThat(status.lastRun().status()).isEqualTo(IngestionRun.Status.FAILED);
        assertThat(status.totalSightings()).isEqualTo(1000);
    }

    @Test
    void emptyFeedDeletesNothing() {
        sync(TestFixtures.records(TestFixtures.SNAPSHOT));
        RunSummary run = sync(List.of());

        assertThat(run.status()).isEqualTo(IngestionRun.Status.SUCCESS);
        assertThat(sightings.count()).isEqualTo(1000);
    }

    @Test
    void unexpectedErrorsStillCloseTheRun() {
        when(api.fetchAllSightings()).thenThrow(new IllegalStateException("boom"));

        RunSummary run = ingestion.runSync(IngestionRun.Trigger.MANUAL).orElseThrow();

        assertThat(run.status()).isEqualTo(IngestionRun.Status.FAILED);
        assertThat(run.error()).isEqualTo("boom");
        assertThat(runs.findByStatus(IngestionRun.Status.RUNNING)).isEmpty();
    }

    @Test
    void secondSyncWhileOneIsRunningIsSkipped() throws Exception {
        CountDownLatch inFetch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.fetchAllSightings()).thenAnswer(inv -> {
            inFetch.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        AtomicReference<Optional<RunSummary>> first = new AtomicReference<>();
        Thread t = new Thread(() -> first.set(ingestion.runSync(IngestionRun.Trigger.SCHEDULED)));
        t.start();
        assertThat(inFetch.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(ingestion.status().syncInProgress()).isTrue();
        assertThat(ingestion.runSync(IngestionRun.Trigger.MANUAL)).isEmpty();

        release.countDown();
        t.join(5_000);
        assertThat(first.get()).isPresent();
        assertThat(ingestion.status().syncInProgress()).isFalse();
    }

    @Test
    void runsLeftRunningByAPreviousProcessAreClosedOnStartup() {
        IngestionRun stale = IngestionRun.start(IngestionRun.Trigger.SCHEDULED);
        ReflectionTestUtils.setField(stale, "startedAt", Instant.parse("2020-01-01T00:00:00Z"));
        stale = runs.save(stale);
        IngestionRun current = runs.save(IngestionRun.start(IngestionRun.Trigger.MANUAL));

        ingestion.closeAbandonedRuns();

        assertThat(runs.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(IngestionRun.Status.FAILED);
        assertThat(runs.findById(stale.getId()).orElseThrow().getErrorMessage()).contains("Abandoned");
        assertThat(runs.findById(current.getId()).orElseThrow().getStatus()).isEqualTo(IngestionRun.Status.RUNNING);
    }

    @Test
    void statusIsStaleUntilTheFirstSuccessfulSync() {
        assertThat(ingestion.status().lastSuccessfulSync()).isNull();
        assertThat(ingestion.status().stale()).isTrue();
        assertThat(ingestion.status().lastRun()).isNull();

        sync(List.of(record("a", "2022-01-01T10:00:00", "Leeds", "Marsh tick")));

        assertThat(ingestion.status().lastSuccessfulSync()).isNotNull();
        assertThat(ingestion.status().stale()).isFalse();
    }
}
