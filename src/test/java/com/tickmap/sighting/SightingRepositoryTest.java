package com.tickmap.sighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class SightingRepositoryTest {

    @Autowired
    SightingRepository repository;

    static NormalizedSighting content(String sourceId, String fingerprint, LocalDateTime at) {
        return new NormalizedSighting(sourceId, at, "London", "London", 51.5, -0.12,
                "Marsh tick", "Ixodes apronophorus", fingerprint);
    }

    static Sighting sighting(String sourceId, String fingerprint, LocalDateTime at) {
        Sighting s = new Sighting(sourceId, sourceId == null ? Sighting.Source.USER : Sighting.Source.UPSTREAM);
        s.applyContent(content(sourceId, fingerprint, at));
        return s;
    }

    @Test
    void savesAndBulkLooksUpBySourceIdAndFingerprint() {
        repository.saveAll(List.of(
                sighting("a", "fp-a", LocalDateTime.of(2020, 1, 1, 10, 0)),
                sighting("b", "fp-b", LocalDateTime.of(2021, 6, 1, 10, 0)),
                sighting(null, "fp-user", LocalDateTime.of(2022, 3, 1, 10, 0))));

        assertThat(repository.findBySourceIdIn(List.of("a", "b", "missing")))
                .extracting(Sighting::getSourceId).containsExactlyInAnyOrder("a", "b");
        assertThat(repository.findByFingerprintIn(List.of("fp-user")))
                .singleElement().extracting(Sighting::getSource).isEqualTo(Sighting.Source.USER);
    }

    @Test
    void rejectsDuplicateSourceIdAtDatabaseLevel() {
        repository.saveAndFlush(sighting("dup", "fp-1", LocalDateTime.of(2020, 1, 1, 0, 0)));
        assertThatThrownBy(() -> repository.saveAndFlush(sighting("dup", "fp-2", LocalDateTime.of(2020, 1, 2, 0, 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateFingerprintAtDatabaseLevel() {
        repository.saveAndFlush(sighting("x", "same-fp", LocalDateTime.of(2020, 1, 1, 0, 0)));
        assertThatThrownBy(() -> repository.saveAndFlush(sighting("y", "same-fp", LocalDateTime.of(2020, 1, 1, 0, 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsManyUserSightingsWithNullSourceId() {
        repository.saveAndFlush(sighting(null, "u1", LocalDateTime.of(2020, 1, 1, 0, 0)));
        repository.saveAndFlush(sighting(null, "u2", LocalDateTime.of(2020, 1, 2, 0, 0)));
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void reportsEarliestAndLatestSighting() {
        assertThat(repository.findEarliestSightedAt()).isEmpty();
        repository.saveAll(List.of(
                sighting("a", "1", LocalDateTime.of(2015, 5, 5, 5, 5)),
                sighting("b", "2", LocalDateTime.of(2012, 1, 3, 20, 40)),
                sighting("c", "3", LocalDateTime.of(2024, 12, 30, 4, 32))));
        assertThat(repository.findEarliestSightedAt()).contains(LocalDateTime.of(2012, 1, 3, 20, 40));
        assertThat(repository.findLatestSightedAt()).contains(LocalDateTime.of(2024, 12, 30, 4, 32));
    }

    @Test
    void applyContentReportsWhetherAnythingChanged() {
        Sighting s = sighting("a", "fp", LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(s.applyContent(content("a", "fp", LocalDateTime.of(2020, 1, 1, 0, 0)))).isFalse();
        assertThat(s.applyContent(content("a", "fp-new", LocalDateTime.of(2020, 1, 2, 0, 0)))).isTrue();
    }
}
