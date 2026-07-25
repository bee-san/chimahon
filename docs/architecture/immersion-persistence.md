# Immersion persistence foundation

Phase 1 introduces an additive SQLDelight schema for event-backed immersion
statistics. Capture and UI remain disabled by default. No legacy JSON file is
read, changed, or removed by this layer.

## Atomic write boundary

An exposure append is one database transaction:

1. Resolve the caller-supplied session and reject an inactive or missing one.
2. Get or create the stable source unit and verify its identity.
3. Insert the caller-supplied event ID and its exposure row.
4. Advance the expected session sequence and live counters.
5. Increment the global invalidation revision.

A retry with the same event ID and canonical payload returns `AlreadyApplied`.
The same ID with a different payload, or the same session sequence owned by a
different event, is a typed identity/sequence conflict. A failure at any point
rolls back the source, event, exposure, counters, and revision together.

The production-facing recorder is feature-flagged. While capture is disabled it
delegates to the no-op recorder and performs no database access. When enabled,
unexpected write failures are isolated into typed results and diagnostics rather
than escaping into reader code.

## Deletion relationships

- A title cannot be deleted while sessions reference it.
- Deleting a session cascades its events, exposures, lookups, and session-bound
  Anki operations.
- Source units survive session deletion so later sessions can reuse stable
  provenance. Deleting their title cascades the source-derived index.
- Deleting a source unit cascades word/character occurrences; event provenance
  becomes unavailable through `ON DELETE SET NULL`.
- Snapshot items, goal check-ins, achievements, and applied-event markers are
  owned by their parent rows and cascade with them.

These rules keep deliberate user deletion distinct from accidental orphaning.
The integrity query still reports orphaned events/occurrences, duplicate
sequences, invalid counters, and stale rollup versions if constraints were
disabled or a database was externally modified.

## Query shapes and indexes

| Query shape | Stable order / predicate | Index |
| --- | --- | --- |
| Global session timeline | `started_at DESC, id DESC` | `immersion_session_time_index` |
| Title sessions | `title_id, started_at DESC, id DESC` | `immersion_session_title_time_index` |
| Recovery queue | `status` plus heartbeat cutoff | `immersion_session_status_time_index` |
| Source index work | status/version then oldest exposure | `immersion_source_unit_index_work_index` |
| Source history | title plus latest exposure | `immersion_source_unit_title_time_index` |
| Event replay | session then timestamp/sequence | `immersion_event_session_time_index` |
| Daily trends | date descending and optional title | `immersion_daily_rollup_*_index` |
| Anki snapshot | profile then request time/id | `immersion_anki_snapshot_profile_time_index` |

Cursor pagination uses `(started_at, id)` rather than offsets, so equal
timestamps cannot duplicate or omit sessions between pages. Repository tests
assert the global timeline query plan uses its stable-order index.

## Versioning and migration

Migration `47.sqm` creates the complete Phase 1 through Phase 10 table envelope
without changing an existing table. Fresh schema creation and an upgrade from
schema version 47 both seed exactly one global metadata row. Schema, capture,
normalization, tokenizer, and rollup versions are stored independently so later
repairs do not need to reinterpret unrelated data.

Migration verification compares the upgraded schema with current create
statements. Restoring the normal pre-migration database backup reverses the
change; this migration has no destructive step and does not touch legacy stores.

## Phase 1 handoff

Available foundations:

- recorder, index, stats, maintenance, goal, and Anki repository contracts;
- real SQLDelight implementations with strict domain mapping;
- stable pagination and revision observation;
- source/event idempotency and abandoned-session recovery;
- typed corruption reports for invalid persisted enums, counters, IDs, flags,
  language tags, and integer ranges;
- fresh-schema, migration, rollback, retry, deletion, paging, recovery,
  concurrency, integrity, and query-plan tests.

Reader instrumentation, legacy import, rollup calculation, and visible UI are
intentionally deferred to later phases.
