package com.tickmap.ingestion;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RejectedRecordRepository extends JpaRepository<RejectedRecord, Long> {

    Page<RejectedRecord> findByRunId(Long runId, Pageable pageable);

    long countByRunId(Long runId);

    // Rows of [reason, count].
    @Query("select r.reason, count(r) from RejectedRecord r where r.runId = :runId group by r.reason order by count(r) desc")
    List<Object[]> countByReasonForRun(Long runId);
}
