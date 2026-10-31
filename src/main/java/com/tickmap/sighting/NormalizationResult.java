package com.tickmap.sighting;

import com.tickmap.ingestion.RejectionReason;

public sealed interface NormalizationResult {

    record Accepted(NormalizedSighting sighting) implements NormalizationResult {}

    record Rejected(String sourceId, RejectionReason reason, String detail) implements NormalizationResult {}
}
