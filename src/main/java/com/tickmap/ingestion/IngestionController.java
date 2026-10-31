package com.tickmap.ingestion;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tickmap.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/ingestion")
@Tag(name = "Ingestion", description = "Sync status, run history and rejected records")
public class IngestionController {

    public record RejectedView(Long id, Long runId, String sourceId, RejectionReason reason, String detail,
                               String rawPayload, Instant rejectedAt) {

        static RejectedView of(RejectedRecord r) {
            return new RejectedView(r.getId(), r.getRunId(), r.getSourceId(), r.getReason(), r.getDetail(),
                    r.getRawPayload(), r.getRejectedAt());
        }
    }

    private final IngestionService ingestion;
    private final RejectedRecordRepository rejections;

    public IngestionController(IngestionService ingestion, RejectedRecordRepository rejections) {
        this.ingestion = ingestion;
        this.rejections = rejections;
    }

    @Operation(summary = "Last successful sync, staleness and the latest run")
    @GetMapping("/status")
    public IngestionService.Status status() {
        return ingestion.status();
    }

    @Operation(summary = "The 20 most recent runs, newest first")
    @GetMapping("/runs")
    public List<IngestionService.RunSummary> runs() {
        return ingestion.recentRuns();
    }

    @Operation(summary = "One run")
    @GetMapping("/runs/{id}")
    public IngestionService.RunSummary run(@PathVariable Long id) {
        return ingestion.findRun(id).orElseThrow(() -> notFound(id));
    }

    @Operation(summary = "Records rejected or flagged as duplicates in a run")
    @GetMapping("/runs/{id}/rejected")
    public PageResponse<RejectedView> rejected(@PathVariable Long id,
                                               @PageableDefault(size = 50, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        if (ingestion.findRun(id).isEmpty()) {
            throw notFound(id);
        }
        return PageResponse.of(rejections.findByRunId(id, pageable), RejectedView::of);
    }

    @Operation(summary = "Trigger a sync now", description = "Runs synchronously. 409 if a sync is already in progress.")
    @PostMapping("/runs")
    public ResponseEntity<IngestionService.RunSummary> trigger() {
        IngestionService.RunSummary run = ingestion.runSync(IngestionRun.Trigger.MANUAL)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "A sync is already in progress"));
        return ResponseEntity.created(URI.create("/api/ingestion/runs/" + run.id())).body(run);
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No ingestion run with id " + id);
    }
}
