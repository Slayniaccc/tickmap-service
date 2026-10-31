package com.tickmap.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tickmap.TestFixtures;
import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;
import com.tickmap.sighting.NormalizationResult;
import com.tickmap.sighting.NormalizedSighting;
import com.tickmap.sighting.SightingNormalizer;

import tools.jackson.databind.JsonNode;

class FixtureValidationTest {

    private final SightingNormalizer normalizer =
            new SightingNormalizer(new CityDirectory(), new SpeciesCatalog(), TestFixtures.FIXED_CLOCK);
    private final BatchDeduplicator deduplicator = new BatchDeduplicator();

    private record Outcome(List<NormalizedSighting> unique, Map<String, RejectionReason> rejectedById,
                           List<RejectionReason> allRejections) {}

    private Outcome process(List<JsonNode> records) {
        List<BatchDeduplicator.Candidate> candidates = new ArrayList<>();
        Map<String, RejectionReason> rejectedById = new HashMap<>();
        List<RejectionReason> all = new ArrayList<>();
        for (JsonNode raw : records) {
            switch (normalizer.normalize(raw)) {
                case NormalizationResult.Accepted a -> candidates.add(new BatchDeduplicator.Candidate(a.sighting(), raw.toString()));
                case NormalizationResult.Rejected r -> {
                    all.add(r.reason());
                    if (r.sourceId() != null) {
                        rejectedById.put(r.sourceId(), r.reason());
                    }
                }
            }
        }
        var deduped = deduplicator.dedupe(candidates);
        deduped.duplicates().forEach(d -> {
            all.add(d.reason());
            // suffixed so a duplicate doesn't overwrite the original's entry
            rejectedById.put(d.candidate().sighting().sourceId() + "#dup", d.reason());
        });
        return new Outcome(deduped.unique().stream().map(BatchDeduplicator.Candidate::sighting).toList(), rejectedById, all);
    }

    @Test
    void messyFixtureEveryRecordEndsUpWhereExpected() {
        List<JsonNode> records = TestFixtures.records(TestFixtures.MESSY);
        Outcome out = process(records);

        assertThat(records).hasSize(25);
        assertThat(out.unique()).extracting(NormalizedSighting::sourceId)
                .containsExactlyInAnyOrder("m1", "m2", "m3", "m4", "m5", "m15", "m16", null, "m24", "m25");

        assertThat(out.rejectedById()).contains(
                entry("m6", RejectionReason.MISSING_DATE),
                entry("m7", RejectionReason.INVALID_DATE),
                entry("m8", RejectionReason.INVALID_DATE),
                entry("m9", RejectionReason.DATE_IN_FUTURE),
                entry("m10", RejectionReason.DATE_TOO_OLD),
                entry("m11", RejectionReason.MISSING_LOCATION),
                entry("m12", RejectionReason.MISSING_LOCATION),
                entry("m13", RejectionReason.MISSING_SPECIES),
                entry("m14", RejectionReason.SPECIES_LATIN_MISMATCH),
                entry("m23", RejectionReason.INVALID_FIELD_TYPE),
                entry("m1#dup", RejectionReason.DUPLICATE_SOURCE_ID),
                entry("m2#dup", RejectionReason.DUPLICATE_SOURCE_ID),
                entry("m19#dup", RejectionReason.DUPLICATE_CONTENT));

        // 25 = 10 kept + 12 invalid + 3 duplicates
        assertThat(out.allRejections()).hasSize(15);
        assertThat(out.allRejections()).filteredOn(r -> r == RejectionReason.NOT_AN_OBJECT).hasSize(2);
    }

    @Test
    void messyFixtureAcceptedRecordsAreProperlyNormalised() {
        Map<String, NormalizedSighting> byId = new HashMap<>();
        process(TestFixtures.records(TestFixtures.MESSY)).unique()
                .forEach(s -> byId.put(String.valueOf(s.sourceId()), s));

        assertThat(byId.get("m2").city()).isEqualTo("Manchester");
        assertThat(byId.get("m2").species()).isEqualTo("Marsh tick");
        assertThat(byId.get("m2").latinName()).isEqualTo("Ixodes apronophorus");
        assertThat(byId.get("m4").sightedAt().getHour()).as("09:15Z in July is 10:15 BST").isEqualTo(10);
        assertThat(byId.get("m5").sightedAt().toLocalTime().toString()).isEqualTo("00:00");
        assertThat(byId.get("m15").species()).isEqualTo("Tree-hole tick");
        assertThat(byId.get("m16").region()).isEqualTo(CityDirectory.UNKNOWN_REGION);
        assertThat(byId.get("m25").species()).isEqualTo("Deer tick");
        assertThat(byId.get("null").city()).isEqualTo("Sheffield");
    }

    @Test
    void realUpstreamSnapshotIsFullyAcceptedWithNoDuplicates() {
        List<JsonNode> records = TestFixtures.records(TestFixtures.SNAPSHOT);
        Outcome out = process(records);

        assertThat(records).hasSize(1000);
        assertThat(out.allRejections()).isEmpty();
        assertThat(out.unique()).hasSize(1000);
        assertThat(out.unique()).allSatisfy(s -> {
            assertThat(s.region()).isNotEqualTo(CityDirectory.UNKNOWN_REGION);
            assertThat(s.latitude()).isNotNull();
            assertThat(s.latinName()).isNotNull();
        });
        assertThat(new HashSet<>(out.unique().stream().map(NormalizedSighting::city).toList())).hasSize(14);
        assertThat(new HashSet<>(out.unique().stream().map(NormalizedSighting::species).toList())).hasSize(5);
    }
}
