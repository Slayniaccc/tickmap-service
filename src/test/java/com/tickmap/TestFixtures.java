package com.tickmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.tickmap.sighting.NormalizationResult;
import com.tickmap.sighting.Sighting;
import com.tickmap.sighting.SightingNormalizer;
import com.tickmap.sighting.SightingRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TestFixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    // 2026-10-31 12:00 UK time
    public static final Clock FIXED_CLOCK = Clock.fixed(
            LocalDateTime.of(2026, 10, 31, 12, 0).atZone(ClockConfig.UK).toInstant(), ClockConfig.UK);

    public static final String MESSY = "/fixtures/messy-sightings.json";
    public static final String SNAPSHOT = "/fixtures/upstream-snapshot-2026-10-31.json";

    private TestFixtures() {
    }

    public static List<JsonNode> records(String resource) {
        try (InputStream in = TestFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + resource);
            }
            List<JsonNode> out = new ArrayList<>();
            JSON.readTree(in).forEach(out::add);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Loads the real snapshot once per context; later calls only remove user-submitted rows.
    public static void seedSnapshot(SightingNormalizer normalizer, SightingRepository sightings) {
        sightings.deleteAll(sightings.findAll().stream().filter(s -> s.getSource() == Sighting.Source.USER).toList());
        if (sightings.count() == 0) {
            sightings.saveAll(records(SNAPSHOT).stream().map(n -> {
                var a = (NormalizationResult.Accepted) normalizer.normalize(n);
                Sighting s = new Sighting(a.sighting().sourceId(), Sighting.Source.UPSTREAM);
                s.applyContent(a.sighting());
                return s;
            }).toList());
        }
    }

    public static JsonNode node(String json) {
        return JSON.readTree(json.replace('\'', '"'));
    }
}
