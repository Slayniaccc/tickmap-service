package com.tickmap.sighting;

import static com.tickmap.TestFixtures.node;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.tickmap.TestFixtures;
import com.tickmap.ingestion.RejectionReason;
import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;

class SightingNormalizerTest {

    private final SightingNormalizer normalizer =
            new SightingNormalizer(new CityDirectory(), new SpeciesCatalog(), TestFixtures.FIXED_CLOCK);

    private NormalizedSighting accepted(String json) {
        NormalizationResult r = normalizer.normalize(node(json));
        assertThat(r).as("expected accepted for %s", json).isInstanceOf(NormalizationResult.Accepted.class);
        return ((NormalizationResult.Accepted) r).sighting();
    }

    private NormalizationResult.Rejected rejected(String json) {
        NormalizationResult r = normalizer.normalize(node(json));
        assertThat(r).as("expected rejected for %s", json).isInstanceOf(NormalizationResult.Rejected.class);
        return (NormalizationResult.Rejected) r;
    }

    @Test
    void acceptsAWellFormedUpstreamRecordAndEnrichesIt() {
        NormalizedSighting s = accepted("{'id':'abc','date':'2022-08-01T06:40:31','location':'Manchester',"
                + "'species':'Marsh tick','latinName':'Ixodes apronophorus'}");

        assertThat(s.sourceId()).isEqualTo("abc");
        assertThat(s.sightedAt()).isEqualTo(LocalDateTime.of(2022, 8, 1, 6, 40, 31));
        assertThat(s.city()).isEqualTo("Manchester");
        assertThat(s.region()).isEqualTo("North West");
        assertThat(s.latitude()).isNotNull();
        assertThat(s.longitude()).isNotNull();
        assertThat(s.species()).isEqualTo("Marsh tick");
        assertThat(s.latinName()).isEqualTo("Ixodes apronophorus");
        assertThat(s.fingerprint()).hasSize(64).matches("[0-9a-f]+");
    }

    @Nested
    class Dates {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
                "2022-08-01T06:40:31        | 2022-08-01T06:40:31",
                "2022-08-01T06:40:31.987    | 2022-08-01T06:40:31",   // fractional seconds dropped
                "2022-08-01T06:40           | 2022-08-01T06:40:00",   // no seconds
                "2022-08-01 06:40:31        | 2022-08-01T06:40:31",   // space separator
                "'  2022-08-01T06:40:31  '  | 2022-08-01T06:40:31",   // padded
                "2022-08-01T05:40:31Z       | 2022-08-01T06:40:31",   // UTC -> BST (+1)
                "2022-01-15T05:40:31Z       | 2022-01-15T05:40:31",   // UTC -> GMT (+0)
                "2022-08-01T08:40:31+03:00  | 2022-08-01T06:40:31",   // other offset -> UK
                "2022-08-01                 | 2022-08-01T00:00:00",   // date only
        })
        void acceptsCommonIsoVariants(String raw, String expected) {
            NormalizedSighting s = accepted("{'id':'x','date':'" + raw + "','location':'London','species':'Marsh tick'}");
            assertThat(s.sightedAt()).isEqualTo(LocalDateTime.parse(expected));
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-date", "01/08/2022", "2022-13-01T00:00:00", "2022-02-30T00:00:00", "yesterday", "2022"})
        void rejectsUnparseableDates(String raw) {
            var r = rejected("{'id':'x','date':'" + raw + "','location':'London','species':'Marsh tick'}");
            assertThat(r.reason()).isEqualTo(RejectionReason.INVALID_DATE);
            assertThat(r.sourceId()).isEqualTo("x");
        }

        @Test
        void rejectsNonStringDates() {
            assertThat(rejected("{'id':'x','date':1659336031,'location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.INVALID_DATE);
            assertThat(rejected("{'id':'x','date':{'y':2022},'location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.INVALID_DATE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{'id':'x','location':'London','species':'Marsh tick'}",
                "{'id':'x','date':null,'location':'London','species':'Marsh tick'}",
                "{'id':'x','date':'   ','location':'London','species':'Marsh tick'}"})
        void rejectsMissingDate(String json) {
            assertThat(rejected(json).reason()).isEqualTo(RejectionReason.MISSING_DATE);
        }

        @Test
        void futureBoundaryIsNowInUkTime() {
            // Fixed clock is 2026-10-31T12:00 UK.
            assertThat(accepted("{'date':'2026-10-31T12:00:00','location':'London','species':'Marsh tick'}")).isNotNull();
            assertThat(rejected("{'date':'2026-10-31T12:00:01','location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.DATE_IN_FUTURE);
        }

        @Test
        void rejectsImplausiblyOldDates() {
            assertThat(rejected("{'date':'1899-12-31T23:59:59','location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.DATE_TOO_OLD);
            assertThat(accepted("{'date':'1900-01-01T00:00:00','location':'London','species':'Marsh tick'}")).isNotNull();
        }
    }

    @Nested
    class Locations {

        @ParameterizedTest
        @ValueSource(strings = {"london", "LONDON", "  London  ", "London."})
        void canonicalisesKnownCities(String raw) {
            assertThat(accepted("{'date':'2020-01-01T00:00:00','location':'" + raw + "','species':'Marsh tick'}").city())
                    .isEqualTo("London");
        }

        @Test
        void keepsUnknownCityTidiedWithUnknownRegionAndNoCoordinates() {
            NormalizedSighting s = accepted("{'date':'2020-01-01T00:00:00','location':'  st   ALBANS ','species':'Marsh tick'}");
            assertThat(s.city()).isEqualTo("St Albans");
            assertThat(s.region()).isEqualTo(CityDirectory.UNKNOWN_REGION);
            assertThat(s.latitude()).isNull();
            assertThat(s.longitude()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{'date':'2020-01-01T00:00:00','species':'Marsh tick'}",
                "{'date':'2020-01-01T00:00:00','location':null,'species':'Marsh tick'}",
                "{'date':'2020-01-01T00:00:00','location':'  ','species':'Marsh tick'}"})
        void rejectsMissingLocation(String json) {
            assertThat(rejected(json).reason()).isEqualTo(RejectionReason.MISSING_LOCATION);
        }

        @Test
        void rejectsNonStringLocation() {
            var r = rejected("{'id':'q','date':'2020-01-01T00:00:00','location':42,'species':'Marsh tick'}");
            assertThat(r.reason()).isEqualTo(RejectionReason.INVALID_FIELD_TYPE);
            assertThat(r.detail()).contains("location");
        }

        @Test
        void rejectsAbsurdlyLongLocation() {
            String longName = "a".repeat(SightingNormalizer.MAX_NAME + 1);
            assertThat(rejected("{'date':'2020-01-01T00:00:00','location':'" + longName + "','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.FIELD_TOO_LONG);
        }
    }

    @Nested
    class Species {

        @Test
        void canonicalisesCaseAndWhitespaceAndFillsMissingLatin() {
            NormalizedSighting s = accepted("{'date':'2020-01-01T00:00:00','location':'London','species':'  fox/BADGER   tick '}");
            assertThat(s.species()).isEqualTo("Fox/badger tick");
            assertThat(s.latinName()).isEqualTo("Ixodes canisuga");
        }

        @Test
        void fillsMissingCommonNameFromLatin() {
            NormalizedSighting s = accepted("{'date':'2020-01-01T00:00:00','location':'London','latinName':'ixodes ARBORICOLA'}");
            assertThat(s.species()).isEqualTo("Tree-hole tick");
            assertThat(s.latinName()).isEqualTo("Ixodes arboricola");
        }

        @Test
        void trustsLatinNameWhenCommonNameIsAnUnknownVariant() {
            NormalizedSighting s = accepted("{'date':'2020-01-01T00:00:00','location':'London',"
                    + "'species':'Fox and badger tick','latinName':'Ixodes canisuga'}");
            assertThat(s.species()).isEqualTo("Fox/badger tick");
        }

        @Test
        void rejectsContradictoryCommonAndLatinNames() {
            var r = rejected("{'date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick','latinName':'Ixodes canisuga'}");
            assertThat(r.reason()).isEqualTo(RejectionReason.SPECIES_LATIN_MISMATCH);
            assertThat(r.detail()).contains("Ixodes apronophorus").contains("Ixodes canisuga");
        }

        @Test
        void rejectsKnownCommonNameWithUnknownLatin() {
            assertThat(rejected("{'date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick','latinName':'Ixodes madeup'}")
                    .reason()).isEqualTo(RejectionReason.SPECIES_LATIN_MISMATCH);
        }

        @Test
        void keepsAnUnknownSpeciesRatherThanLosingTheSighting() {
            NormalizedSighting s = accepted("{'date':'2020-01-01T00:00:00','location':'London','species':'deer   tick'}");
            assertThat(s.species()).isEqualTo("Deer tick");
            assertThat(s.latinName()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"{'date':'2020-01-01T00:00:00','location':'London'}",
                "{'date':'2020-01-01T00:00:00','location':'London','species':' ','latinName':null}",
                "{'date':'2020-01-01T00:00:00','location':'London','latinName':'Ixodes unknownus'}"})
        void rejectsMissingSpecies(String json) {
            assertThat(rejected(json).reason()).isEqualTo(RejectionReason.MISSING_SPECIES);
        }

        @Test
        void rejectsNonStringSpecies() {
            assertThat(rejected("{'date':'2020-01-01T00:00:00','location':'London','species':['Marsh tick']}").reason())
                    .isEqualTo(RejectionReason.INVALID_FIELD_TYPE);
        }
    }

    @Nested
    class IdsAndShape {

        @Test
        void missingOrBlankIdIsAllowedAndBecomesNull() {
            assertThat(accepted("{'date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick'}").sourceId()).isNull();
            assertThat(accepted("{'id':'  ','date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick'}").sourceId()).isNull();
        }

        @Test
        void idIsTrimmed() {
            assertThat(accepted("{'id':' abc ','date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick'}").sourceId())
                    .isEqualTo("abc");
        }

        @Test
        void rejectsNumericIdAsWrongType() {
            assertThat(rejected("{'id':123,'date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.INVALID_FIELD_TYPE);
        }

        @Test
        void rejectsOverlongId() {
            String id = "x".repeat(SightingNormalizer.MAX_SOURCE_ID + 1);
            assertThat(rejected("{'id':'" + id + "','date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick'}").reason())
                    .isEqualTo(RejectionReason.FIELD_TOO_LONG);
        }

        @ParameterizedTest
        @ValueSource(strings = {"'just a string'", "42", "[1,2]", "null", "true"})
        void rejectsNonObjects(String json) {
            assertThat(rejected(json).reason()).isEqualTo(RejectionReason.NOT_AN_OBJECT);
        }

        @Test
        void ignoresUnknownExtraFields() {
            assertThat(accepted("{'date':'2020-01-01T00:00:00','location':'London','species':'Marsh tick','colour':'brown','x':{'y':1}}"))
                    .isNotNull();
        }
    }

    @Nested
    class Fingerprints {

        @Test
        void cosmeticDifferencesProduceTheSameFingerprint() {
            String a = accepted("{'id':'1','date':'2022-08-01T06:40:31','location':'London','species':'Marsh tick','latinName':'Ixodes apronophorus'}").fingerprint();
            String b = accepted("{'id':'2','date':'2022-08-01 06:40:31.5','location':' LONDON ','species':'marsh TICK'}").fingerprint();
            assertThat(a).isEqualTo(b);
        }

        @Test
        void anyRealDifferenceChangesTheFingerprint() {
            String base = accepted("{'date':'2022-08-01T06:40:31','location':'London','species':'Marsh tick'}").fingerprint();
            assertThat(accepted("{'date':'2022-08-01T06:40:32','location':'London','species':'Marsh tick'}").fingerprint()).isNotEqualTo(base);
            assertThat(accepted("{'date':'2022-08-01T06:40:31','location':'Leeds','species':'Marsh tick'}").fingerprint()).isNotEqualTo(base);
            assertThat(accepted("{'date':'2022-08-01T06:40:31','location':'London','species':'Passerine tick'}").fingerprint()).isNotEqualTo(base);
        }
    }

    @Nested
    class TypedPathForApiSubmissions {

        @Test
        void appliesTheSameRulesAsIngestion() {
            var r = normalizer.normalize(null, LocalDateTime.of(2024, 5, 1, 9, 30, 15, 999), " leeds ", "marsh tick", null);
            assertThat(r).isInstanceOfSatisfying(NormalizationResult.Accepted.class, a -> {
                assertThat(a.sighting().city()).isEqualTo("Leeds");
                assertThat(a.sighting().region()).isEqualTo("Yorkshire and the Humber");
                assertThat(a.sighting().latinName()).isEqualTo("Ixodes apronophorus");
                assertThat(a.sighting().sightedAt()).isEqualTo(LocalDateTime.of(2024, 5, 1, 9, 30, 15));
            });
        }

        @Test
        void rejectsFutureDateAndMismatchedSpecies() {
            assertThat(normalizer.normalize(null, LocalDateTime.of(2030, 1, 1, 0, 0), "Leeds", "Marsh tick", null))
                    .isInstanceOfSatisfying(NormalizationResult.Rejected.class,
                            r -> assertThat(r.reason()).isEqualTo(RejectionReason.DATE_IN_FUTURE));
            assertThat(normalizer.normalize(null, LocalDateTime.of(2020, 1, 1, 0, 0), "Leeds", "Marsh tick", "Ixodes canisuga"))
                    .isInstanceOfSatisfying(NormalizationResult.Rejected.class,
                            r -> assertThat(r.reason()).isEqualTo(RejectionReason.SPECIES_LATIN_MISMATCH));
        }

        @Test
        void rejectsNullDateAndBlankLocation() {
            assertThat(normalizer.normalize(null, null, "Leeds", "Marsh tick", null))
                    .isInstanceOfSatisfying(NormalizationResult.Rejected.class,
                            r -> assertThat(r.reason()).isEqualTo(RejectionReason.MISSING_DATE));
            assertThat(normalizer.normalize(null, LocalDateTime.of(2020, 1, 1, 0, 0), "  ", "Marsh tick", null))
                    .isInstanceOfSatisfying(NormalizationResult.Rejected.class,
                            r -> assertThat(r.reason()).isEqualTo(RejectionReason.MISSING_LOCATION));
        }
    }
}
