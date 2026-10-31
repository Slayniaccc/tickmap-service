package com.tickmap.ingestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tickmap.sighting.NormalizedSighting;

/**
 * Dedupes within one fetched batch; the first occurrence wins. Duplicates against rows already
 * in the database are handled by the ingestion service.
 */
@Component
public class BatchDeduplicator {

    public record Candidate(NormalizedSighting sighting, String rawJson) {}

    public record Duplicate(Candidate candidate, RejectionReason reason, String detail) {}

    public record Result(List<Candidate> unique, List<Duplicate> duplicates) {}

    public Result dedupe(List<Candidate> candidates) {
        Map<String, Candidate> bySourceId = new HashMap<>();
        Map<String, Candidate> byFingerprint = new HashMap<>();
        List<Candidate> unique = new ArrayList<>(candidates.size());
        List<Duplicate> duplicates = new ArrayList<>();

        for (Candidate c : candidates) {
            NormalizedSighting s = c.sighting();
            Candidate sameId = s.sourceId() == null ? null : bySourceId.get(s.sourceId());
            if (sameId != null) {
                boolean identical = sameId.sighting().fingerprint().equals(s.fingerprint());
                duplicates.add(new Duplicate(c, RejectionReason.DUPLICATE_SOURCE_ID,
                        "id '" + s.sourceId() + "' appeared earlier in the batch with "
                                + (identical ? "identical" : "conflicting") + " content; kept the first"));
                continue;
            }
            Candidate sameContent = byFingerprint.get(s.fingerprint());
            if (sameContent != null) {
                duplicates.add(new Duplicate(c, RejectionReason.DUPLICATE_CONTENT,
                        "same time, city and species as id '" + sameContent.sighting().sourceId() + "'"));
                continue;
            }
            if (s.sourceId() != null) {
                bySourceId.put(s.sourceId(), c);
            }
            byFingerprint.put(s.fingerprint(), c);
            unique.add(c);
        }
        return new Result(unique, duplicates);
    }
}
