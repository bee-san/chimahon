# Immersion analytics and rollups

Status: implemented in statistics schema/rollup version 2.

## Contract

The analytics API is domain-owned and UI-independent. Every query returns:

- an immutable value;
- `AnalyticsDataQuality`, including legacy share, indexing/text/OCR coverage, Anki capability, and snapshot age;
- `AnalyticsQueryDiagnostics`, containing only query family, a SHA-256 filter hash, row count, and duration.

The diagnostics deliberately contain no title, word, character, query, or source text.

Supported families are overview, trends, titles, vocabulary, characters, sessions, goals, and Anki. Vocabulary, character, and session queries use deterministic paging and stable ID tie-breakers.

## Calendar rules

- Persisted event `timezone_offset_seconds` is authoritative for that event.
- `local_date` is materialized when the event is written and indexed for filtered reads.
- Active durations are interpreted as intervals ending at the event timestamp and split at fixed-offset local midnight without losing milliseconds.
- Adjacent events on a DST boundary use their respective offsets.
- Weeks default to Monday but `ImmersionAnalyticsCalendar` accepts another first day.
- Day/week/month queries generate every requested bucket; days with no rows are returned as zero-valued points.
- Previous comparisons use the immediately preceding equal-length local-date range.

## Rollup model

The canonical event/source tables remain the source of truth. Rebuilds replace only the requested inclusive date range, then regenerate lifetime rows from daily rows in the same transaction.

Daily rows are scoped by:

- local date;
- profile;
- language;
- media kind;
- title;
- event-backed or legacy provenance;
- primary exposure or replay.

Separating replay rows makes the reread/replay filter affect totals, rates, word and character denominators, and title comparisons consistently.

Session counts are assigned to the start date. Event counters are assigned to the event date. Active time is split across midnight. Indexed word/character occurrences are joined to every retained exposure; globally-new values use canonical first-seen timestamps.

Every applied event is recorded with the rollup version. A date/title dirty queue is updated after session lifecycle changes, event/interaction writes, indexing, legacy import, deletion, and explicit rebuild. The battery-aware worker repairs bounded batches; manual date-range rebuild and cancellation are available.

A failed rebuild transaction leaves both the previous rollups and dirty queue intact.

## Filtering and unavailable data

Filters cover date/comparison range, media, profile, language, title, legacy inclusion, character metric, rereads/replays, maturity, and provenance. Empty sets mean “all.”

Legacy aggregates remain visible when requested but never manufacture word, character, source, lookup, or review-history detail. Anki review-history analytics stay unavailable unless the active snapshot explicitly reports that provider capability.

## Performance verification

The implementation avoids date-filtered raw event scans by using `immersion_event_local_date_scope_index`; the query-plan test asserts that index. Daily trends read bounded rollup rows, and detail lists use SQL `LIMIT`/`OFFSET` or keyset paging with stable tie-breakers.

Release-device verification should record cold/warm p50 and p95 for:

1. overview for one day, one year, and all time;
2. 365 daily trend points;
3. first 100 word, character, and session rows;
4. filter changes across a large multi-title library;
5. incremental one-day repair and a full rebuild.

Initial budgets:

- overview and a 365-point trend: p95 below 300 ms;
- first 100 detail rows: p95 below 200 ms;
- first meaningful stats render: below 750 ms.

The JVM fixtures verify one-day and cross-midnight calculations, leap day, DST offsets, week starts, zero-filled trends, replay filtering, migration parity, local-date query plans, and deterministic detail paging. Physical-device p95 measurements are a release qualification item because host JVM timing is not representative of Android storage and power state.
