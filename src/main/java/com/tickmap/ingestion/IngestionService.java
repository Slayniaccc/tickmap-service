package com.tickmap.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tickmap.sighting.NormalizationResult;
import com.tickmap.sighting.NormalizedSighting;
import com.tickmap.sighting.Sighting;
import com.tickmap.sighting.SightingNormalizer;
import com.tickmap.sighting.SightingRepository;
import com.tickmap.upstream.TickApiClient;

import tools.jackson.databind.JsonNode;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    public record RunSummary(Long id, IngestionRun.Status status, IngestionRun.Trigger trigger, Instant startedAt,
                             Instant finishedAt, int fetched, int inserted, int updated, int unchanged,
                             int duplicates, int rejected, Integer upstreamReportedTotal, String error) {

        static RunSummary of(IngestionRun r) {
            return new RunSummary(r.getId(), r.getStatus(), r.getTriggerType(), r.getStartedAt(), r.getFinishedAt(),
                    r.getFetched(), r.getInserted(), r.getUpdated(), r.getUnchanged(), r.getDuplicates(),
                    r.getRejected(), r.getUpstreamReportedTotal(), r.getErrorMessage());
        }
    }

    public record Status(Instant lastSuccessfulSync, boolean stale, boolean syncInProgress, long totalSightings,
                         Integer upstreamReportedTotal, RunSummary lastRun) {}

    private final TickApiClient client;
    private final SightingNormalizer normalizer;
    private final BatchDeduplicator deduplicator;
    private final SightingRepository sightings;
    private final IngestionRunRepository runs;
    private final RejectedRecordRepository rejections;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int chunkSize;
    private final Duration refreshInterval;
    private final Instant startedAt;
    private final ReentrantLock lock = new ReentrantLock();

    public IngestionService(TickApiClient client, SightingNormalizer normalizer, BatchDeduplicator deduplicator,
                            SightingRepository sightings, IngestionRunRepository runs,
                            RejectedRecordRepository rejections, PlatformTransactionManager txManager, Clock clock,
                            @Value("${tickmap.ingestion.chunk-size:500}") int chunkSize,
                            @Value("${tickmap.ingestion.refresh-interval:PT6H}") Duration refreshInterval) {
        this.client = client;
        this.normalizer = normalizer;
        this.deduplicator = deduplicator;
        this.sightings = sightings;
        this.runs = runs;
        this.rejections = rejections;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.chunkSize = chunkSize;
        this.refreshInterval = refreshInterval;
        this.startedAt = clock.instant();
    }

    /** Empty if a sync is already running (scheduled and manual triggers can overlap). */
    public Optional<RunSummary> runSync(IngestionRun.Trigger trigger) {
        if (!lock.tryLock()) {
            log.info("Sync requested ({}) but one is already running; skipping", trigger);
            return Optional.empty();
        }
        try {
            return Optional.of(RunSummary.of(doSync(trigger)));
        } finally {
            lock.unlock();
        }
    }

    private IngestionRun doSync(IngestionRun.Trigger trigger) {
        IngestionRun run = runs.save(IngestionRun.start(trigger));
        AtomicReference<IngestionCounts> progress = new AtomicReference<>(IngestionCounts.ZERO);
        try {
            // If the fetch fails nothing below runs, so the existing data stays as it was.
            List<JsonNode> raw = client.fetchAllSightings();
            Integer reported = client.fetchReportedTotal().orElse(null);
            load(run.getId(), raw, progress);
            if (reported != null && reported != raw.size()) {
                log.warn("Upstream /stats reports {} sightings but the list returned {}", reported, raw.size());
            }
            run.succeed(progress.get(), reported);
            log.info("Ingestion run {} succeeded: {}", run.getId(), progress.get());
        } catch (RuntimeException e) {
            log.error("Ingestion run {} failed; serving existing data", run.getId(), e);
            run.fail(progress.get(), e.getMessage());
        }
        return runs.save(run);
    }

    private void load(Long runId, List<JsonNode> raw, AtomicReference<IngestionCounts> progress) {
        List<BatchDeduplicator.Candidate> candidates = new ArrayList<>(raw.size());
        List<RejectedRecord> invalid = new ArrayList<>();
        for (JsonNode node : raw) {
            switch (normalizer.normalize(node)) {
                case NormalizationResult.Accepted a ->
                        candidates.add(new BatchDeduplicator.Candidate(a.sighting(), node.toString()));
                case NormalizationResult.Rejected r ->
                        invalid.add(new RejectedRecord(runId, r.sourceId(), r.reason(), r.detail(), node.toString()));
            }
        }
        BatchDeduplicator.Result deduped = deduplicator.dedupe(candidates);
        List<RejectedRecord> batchDuplicates = deduped.duplicates().stream()
                .map(d -> new RejectedRecord(runId, d.candidate().sighting().sourceId(), d.reason(), d.detail(),
                        d.candidate().rawJson()))
                .toList();

        for (List<RejectedRecord> chunk : partition(invalid)) {
            tx.executeWithoutResult(s -> rejections.saveAll(chunk));
        }
        for (List<RejectedRecord> chunk : partition(batchDuplicates)) {
            tx.executeWithoutResult(s -> rejections.saveAll(chunk));
        }
        progress.set(new IngestionCounts(raw.size(), 0, 0, 0, batchDuplicates.size(), invalid.size()));

        // One transaction per chunk keeps memory and lock time bounded on large feeds.
        for (List<BatchDeduplicator.Candidate> chunk : partition(deduped.unique())) {
            IngestionCounts c = tx.execute(s -> loadChunk(runId, chunk));
            progress.updateAndGet(p -> p.plus(c));
        }
    }

    private IngestionCounts loadChunk(Long runId, List<BatchDeduplicator.Candidate> chunk) {
        List<String> ids = chunk.stream().map(c -> c.sighting().sourceId()).filter(Objects::nonNull).toList();
        List<String> fingerprints = chunk.stream().map(c -> c.sighting().fingerprint()).toList();
        Map<String, Sighting> bySourceId = index(ids.isEmpty() ? List.of() : sightings.findBySourceIdIn(ids),
                Sighting::getSourceId);
        Map<String, Sighting> byFingerprint = index(sightings.findByFingerprintIn(fingerprints), Sighting::getFingerprint);

        List<Sighting> inserts = new ArrayList<>();
        List<RejectedRecord> duplicates = new ArrayList<>();
        int updated = 0;
        int unchanged = 0;

        for (BatchDeduplicator.Candidate c : chunk) {
            NormalizedSighting n = c.sighting();
            Sighting existing = n.sourceId() == null ? null : bySourceId.get(n.sourceId());
            Sighting owner = byFingerprint.get(n.fingerprint());

            if (existing != null && owner != null && owner != existing) {
                duplicates.add(duplicate(runId, c, "updating it would make it identical to sighting " + owner.getId()));
            } else if (existing != null) {
                // Still applied when unchanged, so region/coordinate fixes in the directory reach old rows.
                if (existing.applyContent(n)) {
                    updated++;
                } else {
                    unchanged++;
                }
            } else if (owner != null) {
                duplicates.add(duplicate(runId, c, "already stored as sighting " + owner.getId()
                        + (owner.getSourceId() == null ? " (user submitted)" : " (upstream id '" + owner.getSourceId() + "')")));
            } else {
                Sighting s = new Sighting(n.sourceId(), Sighting.Source.UPSTREAM);
                s.applyContent(n);
                inserts.add(s);
                byFingerprint.put(n.fingerprint(), s);
            }
        }
        sightings.saveAll(inserts);
        rejections.saveAll(duplicates);
        return new IngestionCounts(0, inserts.size(), updated, unchanged, duplicates.size(), 0);
    }

    private static RejectedRecord duplicate(Long runId, BatchDeduplicator.Candidate c, String detail) {
        return new RejectedRecord(runId, c.sighting().sourceId(), RejectionReason.DUPLICATE_CONTENT, detail, c.rawJson());
    }

    public Status status() {
        Optional<IngestionRun> lastSuccess = runs.findFirstByStatusOrderByFinishedAtDesc(IngestionRun.Status.SUCCESS);
        Instant lastSync = lastSuccess.map(IngestionRun::getFinishedAt).orElse(null);
        boolean stale = lastSync == null || lastSync.isBefore(clock.instant().minus(refreshInterval.multipliedBy(2)));
        return new Status(lastSync, stale, lock.isLocked(), sightings.count(),
                lastSuccess.map(IngestionRun::getUpstreamReportedTotal).orElse(null),
                runs.findFirstByOrderByStartedAtDesc().map(RunSummary::of).orElse(null));
    }

    public List<RunSummary> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc().stream().map(RunSummary::of).toList();
    }

    public Optional<RunSummary> findRun(Long id) {
        return runs.findById(id).map(RunSummary::of);
    }

    // Only runs from before this process started: a run started by this instance is genuinely in progress.
    @EventListener(ApplicationReadyEvent.class)
    public void closeAbandonedRuns() {
        for (IngestionRun run : runs.findByStatus(IngestionRun.Status.RUNNING)) {
            if (run.getStartedAt().isBefore(startedAt)) {
                run.markAbandoned();
                runs.save(run);
                log.warn("Marked ingestion run {} as abandoned", run.getId());
            }
        }
    }

    private <T> List<List<T>> partition(List<T> list) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            out.add(list.subList(i, Math.min(i + chunkSize, list.size())));
        }
        return out;
    }

    private static Map<String, Sighting> index(List<Sighting> rows, Function<Sighting, String> key) {
        return rows.stream().collect(Collectors.toMap(key, s -> s, (a, b) -> a, HashMap::new));
    }
}
