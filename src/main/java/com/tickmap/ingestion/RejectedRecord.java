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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** An upstream record we didn't load, with the reason and the raw JSON. */
@Entity
@Table(name = "rejected_records", indexes = {
        @Index(name = "ix_rejected_run", columnList = "runId"),
        @Index(name = "ix_rejected_reason", columnList = "reason")
})
public class RejectedRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(length = 64)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RejectionReason reason;

    @Column(length = 500)
    private String detail;

    @Lob
    private String rawPayload;

    @Column(nullable = false)
    private Instant rejectedAt;

    protected RejectedRecord() {
    }

    public RejectedRecord(Long runId, String sourceId, RejectionReason reason, String detail, String rawPayload) {
        this.runId = runId;
        this.sourceId = IngestionRun.truncate(sourceId, 64);
        this.reason = reason;
        this.detail = IngestionRun.truncate(detail, 500);
        this.rawPayload = rawPayload;
        this.rejectedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public String getSourceId() { return sourceId; }
    public RejectionReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public String getRawPayload() { return rawPayload; }
    public Instant getRejectedAt() { return rejectedAt; }
}
