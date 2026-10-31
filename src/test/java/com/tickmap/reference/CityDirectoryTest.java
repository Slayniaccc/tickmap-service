package com.tickmap.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CityDirectoryTest {

    private final CityDirectory directory = new CityDirectory();

    @ParameterizedTest
    @ValueSource(strings = {"London", "london", "  LONDON  ", "London."})
    void matchesCaseWhitespaceAndPunctuationVariants(String raw) {
        assertThat(directory.find(raw)).hasValueSatisfying(c -> assertThat(c.name()).isEqualTo("London"));
    }

    @Test
    void resolvesAliasToCanonicalCity() {
        assertThat(directory.find("Newcastle upon Tyne"))
                .hasValueSatisfying(c -> assertThat(c.name()).isEqualTo("Newcastle"));
    }

    @Test
    void mapsCityToRegionAndCoordinates() {
        var glasgow = directory.find("glasgow").orElseThrow();
        assertThat(glasgow.region()).isEqualTo("Scotland");
        assertThat(glasgow.latitude()).isBetween(55.0, 56.5);
        assertThat(glasgow.longitude()).isBetween(-5.0, -3.5);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "Atlantis", "lon don"})
    void returnsEmptyForUnknownOrBlank(String raw) {
        assertThat(directory.find(raw)).isEmpty();
    }

    @Test
    void coversAllFourteenUpstreamCitiesWithoutAliasDuplicates() {
        assertThat(directory.all()).hasSize(14);
        assertThat(directory.all()).allSatisfy(c -> {
            assertThat(c.latitude()).isBetween(49.8, 60.9);   // UK bounding box
            assertThat(c.longitude()).isBetween(-8.7, 1.8);
        });
    }
}
