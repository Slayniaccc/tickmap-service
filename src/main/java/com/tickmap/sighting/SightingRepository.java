package com.tickmap.sighting;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface SightingRepository extends JpaRepository<Sighting, Long>, JpaSpecificationExecutor<Sighting> {

    // One query per ingestion chunk rather than one per record.
    List<Sighting> findBySourceIdIn(Collection<String> sourceIds);

    List<Sighting> findByFingerprintIn(Collection<String> fingerprints);

    Optional<Sighting> findByFingerprint(String fingerprint);

    @Query("select min(s.sightedAt) from Sighting s")
    Optional<LocalDateTime> findEarliestSightedAt();

    @Query("select max(s.sightedAt) from Sighting s")
    Optional<LocalDateTime> findLatestSightedAt();
}
