package com.tickmap.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpeciesCatalogTest {

    private final SpeciesCatalog catalog = new SpeciesCatalog();

    @Test
    void looksUpByCommonNameIgnoringCaseAndWhitespace() {
        assertThat(catalog.findByCommonName("  fox/BADGER   tick "))
                .hasValueSatisfying(s -> assertThat(s.latinName()).isEqualTo("Ixodes canisuga"));
    }

    @Test
    void looksUpByLatinName() {
        assertThat(catalog.findByLatinName("dermacentor frontalis"))
                .hasValueSatisfying(s -> assertThat(s.commonName()).isEqualTo("Passerine tick"));
    }

    @Test
    void unknownOrNullReturnsEmpty() {
        assertThat(catalog.findByCommonName("Deer tick")).isEmpty();
        assertThat(catalog.findByCommonName(null)).isEmpty();
        assertThat(catalog.findByLatinName(null)).isEmpty();
    }

    @Test
    void containsTheFiveUpstreamSpecies() {
        assertThat(catalog.all()).hasSize(5);
    }
}
