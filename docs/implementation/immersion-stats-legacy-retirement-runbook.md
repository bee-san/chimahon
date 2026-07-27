# Immersion statistics legacy-retirement runbook

Status: preparation only. Legacy writes remain enabled.

This runbook defines the evidence and change boundaries required to move from
the current shadow rollout to legacy read-only and eventual retirement. It
does not authorize either transition.

## Current posture

`ImmersionStatsPreferences.CURRENT_ROLLOUT_VERSION` is 3. Its safe defaults are:

| Control | Default |
|---|---|
| Event capture | On |
| Indexing | On |
| Statistics UI | Off, user opt-in |
| Anki inventory | Off, user opt-in |
| Goals | Off, user opt-in |
| Legacy writes | On |

The maintenance screen exposes the rollout state and privacy-safe health/parity
export. Disabling a new surface does not erase data. The legacy-write flag is
kept on by rollout migration so old stores remain a rollback source.

## Legacy inventory

The later retirement change must account for every writer and reader instead
of deleting only the most visible call site.

### Writers

- Novel: `chimahon/.../reader/ReaderViewModel.persistToDisk()` calls
  `BookStorage.saveStatistics(...)`.
- Manga: `app/.../reader/ReaderViewModel.trackMangaStats()` calls
  `MangaStatsStorage.addStats(...)`.
- Anki: `chimahon/.../anki/AnkiCardCreator` calls
  `AnkiStatsStorage.addCard(...)`.

All three writers are guarded by `legacyWritesEnabled()`.

### Readers and compatibility paths

- `LegacyStatsImporter` reads novel, manga, and Anki aggregates with an
  idempotent import ledger.
- `StatsTitlesScreen` and `MangaStatsSheet` still read legacy manga totals.
- `BackupCreator` and `BackupRestorer` retain old manga-stat compatibility.
- Existing legacy files must remain readable through the agreed compatibility
  window even after writers become read-only.

## Promotion evidence

Attach one evidence bundle per candidate release. It must contain:

1. app commit, rollout version, build variant, device model/API and test dates;
2. privacy-safe health/parity JSON before and after each representative flow;
3. novel and manga session/day parity with every policy delta explained;
4. video capture evidence, explicitly marked non-comparable where no legacy
   session/day source exists;
5. zero unexplained duplicates, queue drops, incognito rows, integrity errors,
   merge conflicts, or unrepaired rollup/index backlog;
6. exact backup/restore and repeat-merge totals and identity checks;
7. old-backup tombstone convergence after a deletion;
8. approved capture/query latency, battery, memory and database/raw-text growth;
9. TalkBack, 200% text/display, reduced-motion and min-SDK results;
10. supported-version migration results and rollback rehearsal;
11. user metric/privacy documentation and the proposed release note;
12. a satisfied native corresponding-source/distribution gate for any binary
    release.

The completion audit lists evidence that is currently missing.

## Stage transitions

### Opt-in preview to default UI with dual write

- Keep `legacyWritesEnabled` true.
- Increment the rollout version through a reviewed preference migration.
- Preserve explicit user privacy choices.
- Confirm widget and backup paths use event-backed statistics.
- Retain the UI/capture kill switches.
- Ship only after the promotion evidence passes.

### Dual write to legacy read-only

- Make this a release-boundary change, not a remote or silent preference flip.
- Stop all three legacy writers together under a new versioned migration.
- Do not delete or rewrite old files.
- Keep the importer and rollback readers.
- Publish the reviewed read-only transition note.
- Verify a rollback build can still read the frozen legacy files.
- Soak for at least the evidence period agreed by the maintainers.

### Legacy read-only to retirement

Use a separate PR after the soak period. That PR may remove obsolete writers
and old UI readers, but must:

- retain documented import compatibility;
- preserve backup restore for supported old backups;
- include a repository-wide call-site inventory in its description;
- include before/after migration and rollback fixtures;
- prove the event-backed widget, reports, exports, and backup remain
  authoritative;
- avoid unrelated refactors.

## Rollback

Before retirement, rollback means disabling the new UI/capture flags or
returning to the old query surface while leaving legacy writes and files
intact. Trigger rollback for any criterion in Phase 21, including duplicates,
incognito writes, corruption, failed restore, or unacceptable performance,
battery, or growth.

After legacy read-only begins:

1. stop rollout expansion;
2. collect a privacy-safe health/parity export;
3. restore the previous rollout version/build;
4. verify frozen legacy files are readable;
5. repair/rebuild event-derived state without deleting source-of-truth rows;
6. document the incident before another promotion attempt.

Do not use rollback as permission to merge per-day maxima into event-backed
history.

## Retirement decision record

The approving PR should include:

| Field | Required value |
|---|---|
| Candidate release | Version and commit |
| Evidence window | Start/end and cohort |
| Parity | Novel/manga matched and explained counts |
| Health | Drops, duplicates, integrity, conflict and backlog counts |
| Restore | Devices, repeat count and exact result |
| Performance | Approved p50/p95, battery and growth measurements |
| Accessibility | Device/API and completed checks |
| Migration | Supported source versions and rollback result |
| Distribution | Native compliance evidence location |
| Compatibility end | Last release/date that keeps legacy import support |
| Approvers | Product, engineering and release owners |

Until every field is populated and approved, keep legacy writers enabled and
leave the Phase 21 retirement checkbox open.
