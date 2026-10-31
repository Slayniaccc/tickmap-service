package com.tickmap.sighting;

import java.time.LocalDateTime;

public record NormalizedSighting(
        String sourceId,
        LocalDateTime sightedAt,
        String city,
        String region,
        Double latitude,
        Double longitude,
        String species,
        String latinName,
        String fingerprint) {
}
