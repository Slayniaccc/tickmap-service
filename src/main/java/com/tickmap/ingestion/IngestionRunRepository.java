package com.tickmap.ingestion;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {

    Optional<IngestionRun> findFirstByStatusOrderByFinishedAtDesc(IngestionRun.Status status);

    Optional<IngestionRun> findFirstByOrderByStartedAtDesc();

    List<IngestionRun> findTop20ByOrderByStartedAtDesc();

    List<IngestionRun> findByStatus(IngestionRun.Status status);
}
