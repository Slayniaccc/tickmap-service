package com.tickmap.ingestion;

public record IngestionCounts(int fetched, int inserted, int updated, int unchanged, int duplicates, int rejected) {

    public static final IngestionCounts ZERO = new IngestionCounts(0, 0, 0, 0, 0, 0);

    public IngestionCounts plus(IngestionCounts o) {
        return new IngestionCounts(
                fetched + o.fetched,
                inserted + o.inserted,
                updated + o.updated,
                unchanged + o.unchanged,
                duplicates + o.duplicates,
                rejected + o.rejected);
    }
}
