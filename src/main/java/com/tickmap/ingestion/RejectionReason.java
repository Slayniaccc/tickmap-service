package com.tickmap.ingestion;

/** Why an upstream record was not loaded as a new or updated sighting. */
public enum RejectionReason {
    NOT_AN_OBJECT,
    MISSING_DATE,
    INVALID_DATE,
    DATE_IN_FUTURE,
    DATE_TOO_OLD,
    MISSING_LOCATION,
    MISSING_SPECIES,
    SPECIES_LATIN_MISMATCH,
    FIELD_TOO_LONG,
    DUPLICATE_SOURCE_ID,
    DUPLICATE_CONTENT
}
