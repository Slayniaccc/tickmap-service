package com.tickmap.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tickmap.sighting.NormalizedSighting;

class BatchDeduplicatorTest {

    private final BatchDeduplicator deduplicator = new BatchDeduplicator();

    private static BatchDeduplicator.Candidate c(String sourceId, String fingerprint) {
        return new BatchDeduplicator.Candidate(new NormalizedSighting(sourceId, LocalDateTime.of(2020, 1, 1, 0, 0),
                "London", "London", 51.5, -0.1, "Marsh tick", "Ixodes apronophorus", fingerprint), "{}");
    }

    @Test
    void keepsEverythingWhenThereAreNoDuplicates() {
        var result = deduplicator.dedupe(List.of(c("a", "1"), c("b", "2"), c(null, "3")));
        assertThat(result.unique()).hasSize(3);
        assertThat(result.duplicates()).isEmpty();
    }

    @Test
    void repeatedIdWithIdenticalContentIsDuplicateSourceId() {
        var result = deduplicator.dedupe(List.of(c("a", "1"), c("a", "1")));
        assertThat(result.unique()).hasSize(1);
        assertThat(result.duplicates()).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(RejectionReason.DUPLICATE_SOURCE_ID);
            assertThat(d.detail()).contains("identical");
        });
    }

    @Test
    void repeatedIdWithConflictingContentKeepsFirstAndFlagsConflict() {
        var first = c("a", "1");
        var result = deduplicator.dedupe(List.of(first, c("a", "2")));
        assertThat(result.unique()).containsExactly(first);
        assertThat(result.duplicates()).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(RejectionReason.DUPLICATE_SOURCE_ID);
            assertThat(d.detail()).contains("conflicting");
        });
    }

    @Test
    void sameContentUnderDifferentIdsIsDuplicateContent() {
        var result = deduplicator.dedupe(List.of(c("a", "fp"), c("b", "fp")));
        assertThat(result.unique()).extracting(x -> x.sighting().sourceId()).containsExactly("a");
        assertThat(result.duplicates()).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(RejectionReason.DUPLICATE_CONTENT);
            assertThat(d.detail()).contains("'a'");
        });
    }

    @Test
    void recordsWithoutIdsAreDedupedByContentOnly() {
        var result = deduplicator.dedupe(List.of(c(null, "fp"), c(null, "fp"), c(null, "other")));
        assertThat(result.unique()).hasSize(2);
        assertThat(result.duplicates()).singleElement()
                .extracting(BatchDeduplicator.Duplicate::reason).isEqualTo(RejectionReason.DUPLICATE_CONTENT);
    }

    @Test
    void conflictingDuplicateIdDoesNotBlockItsFingerprintForLaterRecords() {
        // the dropped second "a" must not claim fingerprint 2
        var result = deduplicator.dedupe(List.of(c("a", "1"), c("a", "2"), c("c", "2")));
        assertThat(result.unique()).extracting(x -> x.sighting().sourceId()).containsExactly("a", "c");
    }

    @Test
    void preservesInputOrderAndScalesLinearly() {
        List<BatchDeduplicator.Candidate> many = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            many.add(c("id-" + (i % 50_000), "fp-" + (i % 50_000)));
        }
        long start = System.nanoTime();
        var result = deduplicator.dedupe(many);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(result.unique()).hasSize(50_000);
        assertThat(result.unique().get(0).sighting().sourceId()).isEqualTo("id-0");
        assertThat(result.duplicates()).hasSize(50_000);
        assertThat(ms).as("hash-based dedupe of 100k records").isLessThan(2_000);
    }
}
