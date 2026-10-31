package com.tickmap.sighting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.tickmap.config.TimeConfig;
import com.tickmap.ingestion.RejectionReason;
import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;

import tools.jackson.databind.JsonNode;

/**
 * Validates and normalises one record. Shared by ingestion and POST /api/sightings so both
 * apply the same rules.
 */
@Component
public class SightingNormalizer {

    static final LocalDateTime EARLIEST_ALLOWED = LocalDateTime.of(1900, 1, 1, 0, 0);
    static final int MAX_SOURCE_ID = 64;
    static final int MAX_NAME = 100;
    static final int MAX_LATIN = 150;

    private final CityDirectory cities;
    private final SpeciesCatalog species;
    private final Clock clock;

    public SightingNormalizer(CityDirectory cities, SpeciesCatalog species, Clock clock) {
        this.cities = cities;
        this.species = species;
        this.clock = clock;
    }

    public NormalizationResult normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            return new NormalizationResult.Rejected(null, RejectionReason.NOT_AN_OBJECT,
                    "Expected a JSON object but got " + (raw == null ? "null" : raw.getNodeType()));
        }
        String sourceId = null;
        try {
            sourceId = text(raw, "id");
            String rawDate = text(raw, "date");
            if (rawDate == null) {
                throw new Reject(RejectionReason.MISSING_DATE, "date is missing or blank");
            }
            return accept(sourceId, parseDate(rawDate), text(raw, "location"), text(raw, "species"), text(raw, "latinName"));
        } catch (Reject r) {
            return new NormalizationResult.Rejected(sourceId, r.reason, r.getMessage());
        }
    }

    public NormalizationResult normalize(String sourceId, LocalDateTime sightedAt, String location,
                                         String speciesName, String latinName) {
        try {
            if (sightedAt == null) {
                throw new Reject(RejectionReason.MISSING_DATE, "date is required");
            }
            return accept(blankToNull(sourceId), sightedAt, blankToNull(location), blankToNull(speciesName),
                    blankToNull(latinName));
        } catch (Reject r) {
            return new NormalizationResult.Rejected(sourceId, r.reason, r.getMessage());
        }
    }

    private NormalizationResult accept(String sourceId, LocalDateTime rawAt, String location,
                                       String speciesName, String latinName) {
        checkLength("id", sourceId, MAX_SOURCE_ID);
        LocalDateTime at = checkDateRange(rawAt.truncatedTo(ChronoUnit.SECONDS));

        if (location == null) {
            throw new Reject(RejectionReason.MISSING_LOCATION, "location is missing or blank");
        }
        checkLength("location", location, MAX_NAME);
        Optional<CityDirectory.City> known = cities.find(location);
        String city = known.map(CityDirectory.City::name).orElseGet(() -> titleCase(location));
        String region = known.map(CityDirectory.City::region).orElse(CityDirectory.UNKNOWN_REGION);
        Double lat = known.map(CityDirectory.City::latitude).orElse(null);
        Double lng = known.map(CityDirectory.City::longitude).orElse(null);

        checkLength("species", speciesName, MAX_NAME);
        checkLength("latinName", latinName, MAX_LATIN);
        SpeciesCatalog.Species resolved = resolveSpecies(speciesName, latinName);

        String fingerprint = fingerprint(at, city, resolved.commonName());
        return new NormalizationResult.Accepted(new NormalizedSighting(
                sourceId, at, city, region, lat, lng, resolved.commonName(), resolved.latinName(), fingerprint));
    }

    private SpeciesCatalog.Species resolveSpecies(String common, String latin) {
        if (common == null && latin == null) {
            throw new Reject(RejectionReason.MISSING_SPECIES, "species and latinName are both missing");
        }
        Optional<SpeciesCatalog.Species> byCommon = species.findByCommonName(common);
        Optional<SpeciesCatalog.Species> byLatin = species.findByLatinName(latin);

        if (byCommon.isPresent()) {
            if (latin != null && !byLatin.equals(byCommon)) {
                throw new Reject(RejectionReason.SPECIES_LATIN_MISMATCH, "species '" + common
                        + "' is " + byCommon.get().latinName() + " but latinName is '" + latin + "'");
            }
            return byCommon.get();
        }
        if (byLatin.isPresent()) {
            // The Latin name is the more precise of the two.
            return byLatin.get();
        }
        if (common == null) {
            throw new Reject(RejectionReason.MISSING_SPECIES,
                    "species is missing and latinName '" + latin + "' is not a known species");
        }
        // Unknown species: keep it rather than lose the sighting.
        return new SpeciesCatalog.Species(capitaliseFirst(collapse(common)), latin == null ? null : collapse(latin));
    }

    private LocalDateTime parseDate(String raw) {
        String s = raw.trim();
        try {
            return LocalDateTime.parse(s);                                  // 2022-08-01T06:40:31(.123)
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDateTime.parse(s.replaceFirst(" ", "T"));          // 2022-08-01 06:40:31
        } catch (DateTimeParseException ignored) { }
        try {
            return OffsetDateTime.parse(s)                                  // 2022-08-01T06:40:31Z / +01:00
                    .atZoneSameInstant(TimeConfig.UK).toLocalDateTime();
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDate.parse(s).atStartOfDay();                       // 2022-08-01
        } catch (DateTimeParseException ignored) { }
        throw new Reject(RejectionReason.INVALID_DATE, "date '" + truncate(raw) + "' is not a recognised ISO-8601 date");
    }

    private LocalDateTime checkDateRange(LocalDateTime at) {
        if (at.isAfter(LocalDateTime.now(clock))) {
            throw new Reject(RejectionReason.DATE_IN_FUTURE, "date " + at + " is in the future");
        }
        if (at.isBefore(EARLIEST_ALLOWED)) {
            throw new Reject(RejectionReason.DATE_TOO_OLD, "date " + at + " is before " + EARLIEST_ALLOWED.toLocalDate());
        }
        return at;
    }

    // Excludes the id, so the same sighting under a different id still collides.
    static String fingerprint(LocalDateTime at, String city, String speciesName) {
        String canonical = at + "|" + city.toLowerCase(Locale.ROOT) + "|" + speciesName.toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }

    // null if absent or blank; non-string values are rejected.
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isString()) {
            throw new Reject(field.equals("date") ? RejectionReason.INVALID_DATE : RejectionReason.INVALID_FIELD_TYPE,
                    field + " should be a string but was " + v.getNodeType());
        }
        return blankToNull(v.asString());
    }

    private static void checkLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new Reject(RejectionReason.FIELD_TOO_LONG, field + " is longer than " + max + " characters");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String collapse(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String capitaliseFirst(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String titleCase(String s) {
        StringBuilder out = new StringBuilder();
        for (String word : collapse(s).split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String truncate(String s) {
        return s.length() <= 50 ? s : s.substring(0, 50) + "...";
    }

    private static final class Reject extends RuntimeException {
        final RejectionReason reason;

        Reject(RejectionReason reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }
}
