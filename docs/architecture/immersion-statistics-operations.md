# Immersion statistics operations

Status: release contract for statistics schema 1, index version 2, and rollup
version 2.

## Source of truth and invariants

`immersion_title`, `immersion_session`, `immersion_source_unit`,
`immersion_event`, and `immersion_source_exposure` are authoritative. Daily and
lifetime rollups, FTS, indexed vocabulary/characters, Anki snapshots, and goal
progress are derived or refreshable.

Required invariants:

1. Stable IDs are deterministic or UUID-based and never depend on display text.
2. A session sequence is strictly increasing; idempotent repeats match the
   stored payload hash.
3. Session counters equal accepted event/source deltas.
4. Rollups merge no truth. They rebuild from events and immutable legacy
   aggregates.
5. Tombstones win over older incoming rows and cover dependent rows during
   merge.
6. Incognito and per-title exclusions are evaluated before queue insertion.
7. Raw-text deletion leaves counters and source hashes intact and removes FTS
   documents.
8. Diagnostics contain query-family names, hashes, timings, counts, and error
   codes only—never source text, lookup queries, title names, or credentials.

The maintenance integrity report checks referential integrity, counters,
applied-event versions, dirty ranges, indexing state, and rollup parity.

## Backup and merge protocol

The portable archive carries format/schema versions, typed column metadata,
source tables, goals, exclusions, device/tombstone state, and optional raw
text. Tables are merged in dependency order in chunks of 500 rows.

- An archive newer than the supported format or schema is rejected.
- Identical rows are no-ops.
- A same-identity/different-payload row is written to
  `immersion_merge_conflict` with hashes, not private payload contents.
- Raw text may enrich an otherwise identical text-free source row.
- Tombstoned identities and their dependent rows are skipped.
- FTS and rollups rebuild after merge.
- Restore requires estimated archive expansion plus working space.

Repeat restore and arbitrary device delivery order must produce the same
source rows and totals.

## Retention and deletion

`ImmersionRetentionJob` runs with WorkManager constraints and is safe to retry
after interruption. Its dry-run path uses the same title/time predicate as
deletion. Policies are never, 30 days, one year, and until manually deleted.

Session deletion:

1. captures the affected rollup range;
2. writes a session tombstone;
3. deletes the session and cascading events/exposures;
4. tombstones and deletes source units no longer referenced elsewhere;
5. marks affected rollups dirty and increments the revision.

Full reset previews sessions, time, gross characters, source units, words, and
characters. It tombstones every portable source identity before deletion,
clears derived/cache/import/sync/conflict state, and preserves the tombstones.

## Performance and battery budgets

Capture uses a bounded in-memory queue, batches persistence, accrues active time
at a 15-second heartbeat boundary, and coalesces navigation/exposure updates.
Indexing, retention, repair, and Anki inventory run off the main thread as
bounded/cancellable work.

Release budgets on representative Android hardware:

- capture enqueue p95 below 2 ms with no disk access on the caller thread;
- overview and 365-point trend p95 below 300 ms;
- first 100 detail rows p95 below 200 ms;
- first meaningful stats render below 750 ms;
- no per-frame or per-scroll-tick database writes.

JVM fixtures cover one-day, one-year, and large paged datasets and assert query
plans use local-date, title/time, and indexing work indexes. Physical-device
p50/p95, database growth per hour/10k characters, battery, TalkBack, 200% font,
and reduced-motion checks are release qualification evidence; a host JVM
cannot substitute for those measurements.

## Rollout and rollback

Rollout preferences are versioned. The release defaults event capture,
indexing, analytics UI, Anki inventory, and goals on, while legacy JSON writers
are off. Existing legacy files remain readable and importable during the
compatibility window.

Disable capture/UI or return to the legacy query path if duplicate events,
incognito writes, queue drops, corrupt migration/repair, unacceptable
reader/player performance, excessive storage/battery growth, or non-reproducible
backup restore is observed. Obsolete readers and writers are removed only in a
later, separately reviewable change after field evidence exists.
