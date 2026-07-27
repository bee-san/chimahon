# Immersion analytics and rollups

Status: implemented in statistics schema/rollup version 3.

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

Overview applies one filter contract to current totals, the explicit or equal-length comparison period, inventory metrics, data quality, and streak history. Character comparison ratios use the selected gross, unique-source, or net-progress basis. The reading-stats widget builds the same default Today filter and projects its values directly from the current Overview metrics.

Hourly activity uses a rebuildable `immersion_hourly_rollup` keyed by local date/hour and the same filter dimensions. Event counters and active-duration deltas remain assigned to the event's local occurrence hour; legacy aggregates remain assigned to their synthetic session-start hour. All-time hour-of-day queries therefore scan bounded derived rows rather than `immersion_event`.

Every applied event is recorded with the rollup version. A date/title dirty queue is updated after session lifecycle changes, event/interaction writes, indexing, legacy import, deletion, and explicit rebuild. The battery-aware worker repairs bounded batches; manual date-range rebuild and cancellation are available.

A failed rebuild transaction leaves both the previous rollups and dirty queue intact.

## Filtering and unavailable data

Filters cover date/comparison range, media, profile, language, title, legacy inclusion, character metric, rereads/replays, maturity, and provenance. Empty sets mean “all.”

Legacy aggregates remain visible when requested but never manufacture word, character, source, lookup, or review-history detail. Anki review-history analytics stay unavailable unless the active snapshot explicitly reports that provider capability.

### Vocabulary workbench

`VocabularyFilter` extends the shared filter without coupling word-only choices
to the other tabs. The repository applies search, knownness, script, word
category, part of speech, occurrence bounds, frequency rank, and inclusion
state before stable paging. Date, title, media, profile, language, maturity,
provenance, and replay choices continue to come from `StatsFilter`.

Script and category are deterministic query projections of the retained word
identity and POS metadata. Kanji takes precedence over kana and Latin for mixed
display forms. Name and grammar POS markers take precedence over the
orthographic kana-only category. Missing metadata remains `OTHER`; it is never
guessed from reading speed or Anki state.

User exclusions are keyed by the stable normalized word ID. Applying one:

1. toggles `immersion_word.excluded`;
2. writes or removes the global `WORD` row in `immersion_exclusion`, so future
   indexing honors the same choice;
3. marks every affected date/title rollup dirty; and
4. leaves source occurrences and retained text untouched so the choice is
   reversible.

Excluded words do not contribute to vocabulary growth, unique-word coverage,
or Anki word denominators. Character encounters are unaffected. The
maintenance screen documents this policy, and the Vocabulary tab can show
included, excluded, or both sets. Bulk changes show the selected identities
and filtered occurrence count before confirmation.

Vocabulary CSV export receives the same shared and word-specific filters and
sort as the visible list. It pages through the repository, includes a schema
version plus frequency/JLPT/grade/script/category/inclusion metadata, applies
spreadsheet-formula protection, and never includes raw source text.

## Goal calendar and edit policy

Goal history follows the same event calendar contract: completed activity remains
assigned to the local date and offset recorded when the event was captured.
The meaning of “today” and all future goal boundaries follows the device's
current timezone when progress is evaluated. Changing timezone can therefore
change which current day is in progress, but it does not move historical
activity between dates.

Weekday schedules store an explicit multiplier for every ISO weekday. A zero
multiplier is a rest day and does not break a daily streak; fractional values
scale that day's target. Goal edits apply prospectively from the current local
day, retain the same goal identity and creation timestamp, and upsert only the
goal definition. Existing check-ins and immutable achievement rows are not
deleted or rewritten. Finish-title goals use a user-entered source-unit target
scoped to one title until the persistence model has a trustworthy known title
length.

## Performance verification

The implementation avoids date-filtered raw event scans by using `immersion_event_local_date_scope_index`; the query-plan test asserts that index. Hour-of-day and daily trends read bounded rollup rows, and detail lists use SQL `LIMIT`/`OFFSET` or keyset paging with stable tie-breakers.

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
