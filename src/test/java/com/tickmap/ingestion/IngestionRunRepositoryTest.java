package com.tickmap.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class IngestionRunRepositoryTest {

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    RejectedRecordRepository rejected;

    @Test
    void findsLatestSuccessfulRunIgnoringLaterFailures() throws Exception {
        IngestionRun ok = IngestionRun.start(IngestionRun.Trigger.SCHEDULED);
        ok.succeed(new IngestionCounts(10, 10, 0, 0, 0, 0), 10);
        runs.saveAndFlush(ok);

        Thread.sleep(5);
        IngestionRun failed = IngestionRun.start(IngestionRun.Trigger.SCHEDULED);
        failed.fail(IngestionCounts.ZERO, "upstream down");
        runs.saveAndFlush(failed);

        assertThat(runs.findFirstByStatusOrderByFinishedAtDesc(IngestionRun.Status.SUCCESS))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(ok.getId()));
        assertThat(runs.findFirstByOrderByStartedAtDesc())
                .hasValueSatisfying(r -> assertThat(r.getStatus()).isEqualTo(IngestionRun.Status.FAILED));
    }

    @Test
    void truncatesVeryLongErrorMessages() {
        IngestionRun run = IngestionRun.start(IngestionRun.Trigger.MANUAL);
        run.fail(IngestionCounts.ZERO, "x".repeat(5000));
        assertThat(runs.saveAndFlush(run).getErrorMessage()).hasSize(2000);
    }

    @Test
    void storesRejectedRecordsWithRawPayloadAndCountsByReason() {
        IngestionRun run = runs.saveAndFlush(IngestionRun.start(IngestionRun.Trigger.MANUAL));
        String bigPayload = "{\"id\":\"x\",\"pad\":\"" + "a".repeat(10_000) + "\"}";
        rejected.saveAll(List.of(
                new RejectedRecord(run.getId(), "x", RejectionReason.MISSING_DATE, "date is missing", bigPayload),
                new RejectedRecord(run.getId(), "y", RejectionReason.MISSING_DATE, "date is missing", "{}"),
                new RejectedRecord(run.getId(), "z", RejectionReason.DUPLICATE_CONTENT, "same as w", "{}")));
        rejected.flush();

        assertThat(rejected.countByRunId(run.getId())).isEqualTo(3);
        assertThat(rejected.findByRunId(run.getId(), PageRequest.of(0, 10)).getContent())
                .anySatisfy(r -> assertThat(r.getRawPayload()).hasSize(bigPayload.length()));
        assertThat(rejected.countByReasonForRun(run.getId()))
                .first().satisfies(row -> {
                    assertThat(row[0]).isEqualTo(RejectionReason.MISSING_DATE);
                    assertThat(row[1]).isEqualTo(2L);
                });
    }
}
