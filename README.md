# Tickmap Service

Backend for a UK tick sightings tracker, built for the Elanco Placement Programme backend task.

**Author:** _your name here_

It pulls tick sighting data from the Elanco API into its own database, cleans and deduplicates it,
and serves search, aggregate stats and ingestion monitoring over a REST API, with Swagger UI for
exploring it.

- Java 21, Spring Boot 4.1, Spring Data JPA (Hibernate 7), H2
- 31 source files, 18 test files, 196 tests

---

## Contents

1. [Running it](#running-it)
2. [API overview](#api-overview)
3. [Data source: what the upstream API looks like](#data-source-what-the-upstream-api-looks-like)
4. [Architecture](#architecture)
5. [How the data is handled](#how-the-data-is-handled)
6. [Error handling](#error-handling)
7. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
8. [Testing](#testing)
9. [What I'd do with more time](#what-id-do-with-more-time)

---

## Running it

**Prerequisites:** JDK 21 or newer. Nothing else: Maven comes with the wrapper and the database
is embedded.

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

On startup the app syncs from the Elanco API (after 2 seconds), then again every 6 hours.

| URL | What |
|---|---|
| http://localhost:8080/ | Map and trend chart page |
| http://localhost:8080/swagger-ui.html | Swagger UI: try every endpoint |
| http://localhost:8080/api/ingestion/status | Sync status |
| http://localhost:8080/h2-console | Database console (JDBC URL `jdbc:h2:file:./data/tickmap`, user `sa`, no password) |

**Run the tests:**

```bash
./mvnw test
```

Tests use an in-memory database and never call the real API.

**Build a jar:**

```bash
./mvnw package
java -jar target/tickmap-service-0.0.1-SNAPSHOT.jar
```

### Configuration

All in `src/main/resources/application.properties`. Any value can be overridden on the command
line, e.g. `--tickmap.ingestion.refresh-interval=PT1H`.

| Property | Default | Meaning |
|---|---|---|
| `tickmap.upstream.base-url` | `https://dev-task.elancoapps.com/data` | Elanco API base URL |
| `tickmap.upstream.connect-timeout` / `read-timeout` | `5s` / `15s` | HTTP timeouts |
| `tickmap.upstream.retry.max-attempts` | `4` | Attempts per call, including the first |
| `tickmap.upstream.retry.initial-backoff` / `multiplier` / `max-backoff` | `1s` / `2.0` / `30s` | Exponential backoff |
| `tickmap.ingestion.enabled` | `true` | Turn scheduled syncing off |
| `tickmap.ingestion.initial-delay` / `refresh-interval` | `PT2S` / `PT6H` | When syncs run |
| `tickmap.ingestion.chunk-size` | `500` | Records per database transaction |

---

## API overview

Full details and a live "try it out" are in Swagger UI. All errors come back as
[RFC 9457 ProblemDetail](https://www.rfc-editor.org/rfc/rfc9457) JSON.

### Sightings

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/sightings` | Search, paginated. Filters: `from`, `to`, `city`, `region`, `species`. Paging: `page`, `size` (max 200), `sort` |
| `GET` | `/api/sightings/{id}` | One sighting |
| `POST` | `/api/sightings` | Report a new sighting (validated, deduplicated) |

```bash
# Marsh ticks in Scotland during 2020, oldest first
curl "localhost:8080/api/sightings?region=Scotland&species=marsh%20tick&from=2020-01-01&to=2020-12-31&sort=sightedAt,asc"

# Report a sighting
curl -X POST localhost:8080/api/sightings -H 'Content-Type: application/json' \
  -d '{"date":"2025-06-14T17:30:00","location":"Leeds","species":"Marsh tick"}'
```

- `from` and `to` accept a date (`2020-01-01`) or a date-time (`2020-01-01T09:30:00`). `to` is
  inclusive, so `to=2020-12-31` includes all of 31 December.
- City and species are matched case-insensitively, and known aliases resolve
  (`Newcastle upon Tyne` → `Newcastle`).

### Stats

Every stats endpoint accepts the same filters as search.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/stats/summary` | Total sightings, date range covered, number of cities, regions and species |
| `GET` | `/api/stats/regions` | Sightings per region, most first |
| `GET` | `/api/stats/cities` | Sightings per city, with coordinates for a map |
| `GET` | `/api/stats/species` | Sightings per species |
| `GET` | `/api/stats/trends?interval=week\|month` | Counts per ISO week (Monday start) or calendar month, empty periods included as 0 |

```bash
curl "localhost:8080/api/stats/trends?interval=month&city=London&from=2023-01-01&to=2023-12-31"
```

### Ingestion

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/ingestion/status` | `lastSuccessfulSync`, `stale` flag, totals, the latest run |
| `GET` | `/api/ingestion/runs` | The last 20 runs with their counts |
| `GET` | `/api/ingestion/runs/{id}` | One run |
| `GET` | `/api/ingestion/runs/{id}/rejected` | Records rejected in that run, with reason and raw JSON (paginated) |
| `POST` | `/api/ingestion/runs` | Trigger a sync now (409 if one is already running) |

Example status:

```json
{
  "lastSuccessfulSync": "2026-10-31T12:40:04.041Z",
  "stale": false,
  "syncInProgress": false,
  "totalSightings": 1000,
  "upstreamReportedTotal": 1000,
  "lastRun": { "id": 1, "status": "SUCCESS", "fetched": 1000, "inserted": 1000, "updated": 0,
               "unchanged": 0, "duplicates": 0, "rejected": 0, "error": null }
}
```

### Map and trend page

`src/main/resources/static/index.html` is a small single-file page that uses the API. It has no
build step: it's plain JavaScript with Leaflet and Chart.js from a CDN.

- **Map:** one circle per city, with area proportional to its sighting count. Hover for city,
  region and count. Click a city to filter the trend.
- **Trend:** monthly or weekly sightings as a line chart with a hover crosshair.
- **Filters:** species and date range apply to both views. A status line shows the last sync and
  whether data is stale.
- A table under the map lists the same numbers, so nothing relies on reading the chart alone.
  Light and dark mode follow the OS setting.

---

## Data source: what the upstream API looks like

I started by inspecting `https://dev-task.elancoapps.com/` with `curl`. The root is a JavaScript
docs page, and the OpenAPI spec is embedded in its bundle. The spec's server entry is `/data`, so
the real base URL is `https://dev-task.elancoapps.com/data`.

| Endpoint | Returns |
|---|---|
| `/tick-sightings` | **All** sightings in one array. No pagination or date filtering |
| `/tick-sightings/{id}`, `/city/{city}`, `/species/{species}` | Exact, case-sensitive lookups |
| `/stats` | `{"total_sightings": 1000, ...}` |
| `/health` | `{"status": "healthy"}` |

Records look like this:

```json
{"id": "02WNholuSg6ndCk4c1dA", "date": "2022-08-01T06:40:31", "location": "Manchester",
 "species": "Marsh tick", "latinName": "Ixodes apronophorus"}
```

Things that shaped the design:

- **It is rate limited.** The spec documents a 429, plus 500 errors.
- **No pagination, no time-range filter, no combined filters.** The brief's search requirements
  can't be met by passing requests through.
- **No coordinates or regions**, only a city name.
- **Dates have no timezone.**
- **Today's data is clean.** It has 1000 records, 14 cities and 5 species, each with one Latin
  name, no duplicates or gaps, and dates from 2012 to 2024. The brief still says to plan for
  duplicates and incomplete data, so the pipeline handles them anyway. The tests prove it with a
  deliberately messy fixture.

---

## Architecture

```
                         every 6h + on startup, or POST /api/ingestion/runs
                                          │
 Elanco API ──► TickApiClient ──► SightingNormalizer ──► BatchDeduplicator ──► IngestionService ──► H2
 (/data)        timeouts,         validate, clean,       drop repeats          match DB rows by       (file)
                retries with      add region + coords,   within the batch      id / fingerprint,       │
                backoff           fingerprint            │                     chunked transactions    │
                                     │                   │                          │                  │
                                     └── rejects ────────┴──────────────────────────┴─► rejected_records
                                                                                                       │
 REST API ◄─────────────────────────────────────────────────────────────────────────────────────────┘
   /api/sightings   search (JPA Specification) + POST (same normaliser)
   /api/stats       GROUP BY queries
   /api/ingestion   status, run history, rejected records
```

| Package | Responsibility |
|---|---|
| `upstream` | HTTP client, timeouts, retry with exponential backoff, failure classification |
| `sighting` | Entity, normaliser (validation and cleaning), search filter, search and create API |
| `ingestion` | Sync orchestration, batch dedupe, run history, rejected records, scheduler, status API |
| `stats` | Aggregation queries and stats API |
| `reference` | Known cities (region, lat/lng) and species (common ↔ Latin name) |
| `web` | Global error handler, page response shape |

**Tables:** `sightings`, `ingestion_runs`, `rejected_records`.

---

## How the data is handled

### Ingest, don't proxy

The service copies the upstream data into its own database and serves everything from there. It
does not forward each request to the Elanco API. The reasons:

- The upstream can't filter by time range or combine filters, and can't paginate. A local database
  can do all three.
- The upstream is rate limited. One bulk call per sync uses far less of that allowance than one
  call per user request.
- If the upstream goes down, users still get the last good data.
- Responses are fast and don't depend on someone else's latency.

The trade-off is freshness. Data can be up to 6 hours old (configurable), and the status endpoint
says exactly how old it is.

### Large datasets

- **One bulk call** per sync, parsed into a list of raw JSON nodes.
- **Chunked loading**, 500 records per transaction by default. Each chunk looks up existing rows
  with two `IN` queries (by id and by fingerprint) rather than one query per record, then inserts
  in JDBC batches. Memory use and lock time stay bounded however large the feed gets.
- **Indexes** on `sightedAt`, `(city, sightedAt)`, `(region, sightedAt)` and `species`, matching
  the search and stats filters.
- **Aggregation in the database.** Stats queries are `GROUP BY`, so only grouped rows come back,
  never individual sightings. Trends group by day, giving at most one row per day, however many
  sightings there are.
- **Page size is capped at 200**, so one request can't pull the whole table.

### Duplicates

Each sighting has two unique keys:

1. `sourceId`: the upstream id.
2. `fingerprint`: a SHA-256 of the *normalised* time, city and species. `"London"` and
   `" LONDON "` produce the same hash. The id is deliberately excluded, so the same sighting
   reported under two ids still collides.

Dedupe happens at two levels:

- **Within a batch** (`BatchDeduplicator`): a repeated id is flagged as `DUPLICATE_SOURCE_ID`
  (noting whether its content was identical or conflicting), and repeated content under another id
  as `DUPLICATE_CONTENT`. The first record wins.
- **Against the database** (`IngestionService`): a known id with changed content is an
  **update**, and with unchanged content it's **unchanged**. New content matching an existing
  fingerprint is a duplicate. An update that would make one row identical to another is flagged,
  not applied.

The database's unique constraints are the final backstop. Re-running a sync with the same data
changes nothing, so syncs are idempotent.

### Incomplete and inconsistent data

Every record goes through `SightingNormalizer` and comes out either clean or rejected with a
reason. Rejected records are stored in `rejected_records` **with the raw JSON**, so nothing is
silently lost and problems can be audited.

| Problem | What happens |
|---|---|
| Missing, blank or non-string date | Rejected (`MISSING_DATE` / `INVALID_DATE`) |
| Date variants: `2022-08-01 06:40:31`, fractional seconds, `...Z` / `+01:00`, date only | Accepted. UTC and offsets are converted to UK time (BST-aware) |
| Date in the future or before 1900 | Rejected (`DATE_IN_FUTURE` / `DATE_TOO_OLD`) |
| City in odd case or spacing, or an alias | Canonicalised (`" manchester "` → `Manchester`) |
| Unknown city | **Kept**, with region `Unknown` and no coordinates. It's still a real sighting |
| Missing location | Rejected (`MISSING_LOCATION`) |
| Missing Latin name, or missing common name | Filled in from the species catalogue |
| Common and Latin names point to different species | Rejected (`SPECIES_LATIN_MISMATCH`). We can't tell which is right |
| Unknown species | Kept as given (tidied) |
| Wrong JSON type (e.g. `"location": 42`), not an object, field too long | Rejected (`INVALID_FIELD_TYPE` / `NOT_AN_OBJECT` / `FIELD_TOO_LONG`) |
| Unknown extra fields | Ignored |

Upstream timestamps have no zone. They're UK sightings, so they're treated as UK local time.

User submissions through `POST /api/sightings` go through the same normaliser, so the API and
ingested data always follow the same rules.

---

## Error handling

### Upstream failures

| Failure | Behaviour |
|---|---|
| Timeout, connection refused, DNS failure | Retried |
| 408, 425, **429**, any 5xx | Retried. `Retry-After` is honoured, capped at the max backoff |
| Truncated or empty JSON | Retried (usually a cut-off response) |
| 404 and other 4xx | Fails immediately. Retrying returns the same answer and wastes rate limit |
| Valid JSON of the wrong shape (an object instead of a list) | Fails immediately |

Retries use **exponential backoff with jitter**: 1s, 2s, 4s… capped at 30s, each randomised to
50–100% so clients don't retry in lockstep. Connect and read timeouts mean a server that never
replies can't hang the sync.

### If a sync fails

- The fetch happens before anything is written, so **existing data is untouched** and the API
  carries on serving it.
- The run is recorded as `FAILED` with the error. `lastSuccessfulSync` still points to the last
  good run, and `stale` becomes `true` once that's more than two refresh intervals old.
- If `/stats` disagrees with the number of records received, a warning is logged and the reported
  total is stored on the run.
- A lock stops a manual trigger overlapping a scheduled run (the manual trigger gets a 409).
- If the app is killed mid-sync, the run left as `RUNNING` is marked `FAILED` ("abandoned") on the
  next startup.

### Our own API

`GlobalExceptionHandler` returns a ProblemDetail with a timestamp for every error:

- **400**: validation failures list each bad field. Unreadable JSON names the field and the
  expected date format. Bad query parameters and sort fields are explained.
- **404, 405, 415**: standard.
- **409**: duplicate sighting (includes `existingId`), sync already running, or a constraint
  violation.
- **500**: a generic message plus an `errorId` that matches the logged stack trace. Exception
  messages and class names never reach the client.

---

## Design decisions and trade-offs

- **Java and Spring Boot.** Validation, scheduling, JPA, OpenAPI and ProblemDetail come built in,
  so the code can focus on the data handling. Spring is widely used for enterprise backends.
- **H2 as a file, not in memory.** Data survives a restart, so the service can serve the last good
  data even if the upstream is down when it starts. No setup is needed for a reviewer. For
  production I'd use PostgreSQL. The queries are standard JPA, and the trends query avoids
  database-specific date functions.
- **Raw JSON nodes, not typed DTOs, from the client.** Deserialising straight into Java objects
  would fail the whole batch over one bad date. Raw nodes let the validator decide record by
  record.
- **One normaliser for ingestion and POST**, so the rules can't drift apart.
- **Keep unknown cities and species** rather than reject them. Losing a real sighting because our
  lookup table is incomplete is the worse outcome.
- **Never delete on sync.** If a record disappears upstream we keep it. An empty or broken
  response can't wipe the database. The downside is that genuine upstream deletions aren't
  reflected (see below).
- **Weekly trends use ISO weeks.** Grouping by day in SQL and folding into weeks in Java keeps
  week boundaries correct across New Year, and keeps the query portable.
- **Manual sync is synchronous.** A sync takes under a second at this data size, so returning the
  finished run is simpler than polling.

---

## Testing

```bash
./mvnw test     # 196 tests, about 15 seconds
```

| Test class | Tests | Covers |
|---|---|---|
| `SightingNormalizerTest` | 57 | Every validation and cleaning rule: date formats and timezones, boundaries, cities, species, types, fingerprints |
| `FixtureValidationTest` | 3 | A 25-record **messy fixture** where every record's outcome is asserted, plus the **real 1000-record snapshot**, which must load with zero rejections |
| `BatchDeduplicatorTest` | 7 | Repeated ids, conflicting content, content duplicates, 100k records in under 2s |
| `RetryExecutorTest` | 11 | Backoff sequence, cap, jitter bounds, `Retry-After`, give-up, no retry on 4xx or on bugs, interruption |
| `TickApiClientTest` | 14 | Mock server: 429 → 500 → 502 → success, outage, 404, truncated JSON, empty body, wrong shape, socket timeout, `/stats` |
| `TickApiClientTimeoutTest` | 2 | **Real HTTP server**: read timeout actually fires, refused connection retried |
| `IngestionServiceTest` | 12 | Messy feed, idempotent re-runs, updates, update collisions, user/upstream duplicates, outage keeps data, empty feed, concurrent syncs, crash recovery, staleness |
| `IngestionControllerTest` | 6 | Status, trigger, failed run, paged rejected records, 404, 409 while running |
| `SightingControllerTest` | 31 | Every filter, combined filters, aliases, inclusive dates, paging, page cap, sorting, bad input; POST success, duplicates, rule violations |
| `StatsControllerTest` | 13 | Region/city/species/summary counts, monthly and weekly trends, ISO week across New Year, zero-filling, bad input |
| `GlobalExceptionHandlerTest` | 12 | 400/404/405/409/415/500 shapes; 500s leak no internals |
| `EndToEndIngestionTest` | 2 | **Whole app, no mocks**, against a fake upstream: 429 → 500 → messy data, total outage, wrong shape, recovery with a correction, hanging server |
| Repository and reference tests | 25 | Unique constraints, bulk lookups, city/species lookups |

Two choices worth pointing out:

- **Expected values come from the raw data, not from our code.** Search and stats tests compute
  their expected counts straight from the upstream JSON snapshot, and weekly buckets are checked
  against the JDK's ISO week fields. A bug in our logic can't pass by producing the matching wrong
  answer.
- **Time is frozen** in validation tests (an injected `Clock`), and retry tests swap out the sleep,
  so the suite gives the same result every run and doesn't wait for real backoffs.

---

## What I'd do with more time

- **PostgreSQL with Flyway migrations** instead of H2 with `ddl-auto`, and Testcontainers for
  integration tests against the real database.
- **Multi-instance safety.** The sync lock is in-process. Running several instances would need a
  distributed lock (e.g. ShedLock) so only one syncs at a time.
- **Upstream deletions.** Track `lastSeenRunId` per row and soft-delete records missing from
  several consecutive syncs.
- **Auth and rate limiting** on `POST /api/sightings` and the manual sync trigger.
- **Caching** of stats responses, invalidated after each sync.
- **Location search by radius** (`near=lat,lng&radiusKm=`) as well as by city or region.
- **Metrics** (Micrometer): rejections per reason, sync duration, upstream error rates, plus an
  alert when `stale` goes true.
- **Replaying rejected records** after fixing a rule or adding a city to the lookup table.
- **A Dockerfile and CI** (GitHub Actions running the tests on each push).
- **AI/ML extension:** seasonal forecasting of sightings per region (the monthly trend is a clear
  seasonal time series), or anomaly detection to flag unusual spikes in a city.
