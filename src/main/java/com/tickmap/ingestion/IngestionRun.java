package com.tickmap.ingestion;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** One sync attempt and its outcome. */
@Entity
@Table(name = "ingestion_runs", indexes = {
        @Index(name = "ix_ingestion_runs_status_finished", columnList = "status, finishedAt")
})
public class IngestionRun {

    public enum Status { RUNNING, SUCCESS, FAILED }

    public enum Trigger { SCHEDULED, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Trigger triggerType;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    private int fetched;
    private int inserted;
    private int updated;
    private int unchanged;
    private int duplicates;
    private int rejected;

    // Total reported by upstream /stats, if it answered.
    private Integer upstreamReportedTotal;

    @Column(length = 2000)
    private String errorMessage;

    protected IngestionRun() {
    }

    public static IngestionRun start(Trigger trigger) {
        IngestionRun run = new IngestionRun();
        run.status = Status.RUNNING;
        run.triggerType = trigger;
        run.startedAt = Instant.now();
        return run;
    }

    public void succeed(IngestionCounts c, Integer upstreamReportedTotal) {
        applyCounts(c);
        this.upstreamReportedTotal = upstreamReportedTotal;
        this.status = Status.SUCCESS;
        this.finishedAt = Instant.now();
    }

    public void fail(IngestionCounts c, String message) {
        applyCounts(c);
        this.status = Status.FAILED;
        this.errorMessage = truncate(message, 2000);
        this.finishedAt = Instant.now();
    }

    // For a run left RUNNING because the app stopped mid-sync.
    public void markAbandoned() {
        this.status = Status.FAILED;
        this.errorMessage = "Abandoned: the application stopped while this run was in progress";
        this.finishedAt = Instant.now();
    }

    private void applyCounts(IngestionCounts c) {
        this.fetched = c.fetched();
        this.inserted = c.inserted();
        this.updated = c.updated();
        this.unchanged = c.unchanged();
        this.duplicates = c.duplicates();
        this.rejected = c.rejected();
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() { return id; }
    public Status getStatus() { return status; }
    public Trigger getTriggerType() { return triggerType; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getFetched() { return fetched; }
    public int getInserted() { return inserted; }
    public int getUpdated() { return updated; }
    public int getUnchanged() { return unchanged; }
    public int getDuplicates() { return duplicates; }
    public int getRejected() { return rejected; }
    public Integer getUpstreamReportedTotal() { return upstreamReportedTotal; }
    public String getErrorMessage() { return errorMessage; }
}
