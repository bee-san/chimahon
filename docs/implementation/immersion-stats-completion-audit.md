# Immersion statistics completion audit

Audit date: 2026-07-27

Branch: `fix/stats-atomic-session-start`

Audited baseline: `12fa0c219`

## Verdict

The immersion-statistics program is not 100% complete and is not ready for
legacy retirement or public binary distribution.

Phases 13-21 contain 144 implementation checkboxes. This audit found local code
and automated-test evidence for 118 and left 26 open. A checked roadmap item
means only that its implementation is locally evidenced. Phase definitions of
done, the required test matrix, and all seven final acceptance scenarios still
apply independently.

The default rollout remains version 3 shadow mode:

- capture and indexing on;
- UI, Anki inventory, and goals opt-in;
- legacy JSON writes on;
- old files and import compatibility retained.

## Phase evidence

| Phase | Locally evidenced | Open | Primary evidence | Phase status |
|---|---:|---:|---|---|
| 13 - Titles | 13 | 0 | `ImmersionTitleIdentity`, `StatsTitleMetadataResolver`, title mutation previews/rollback, title analytics tests | Implementation checklist evidenced; paging performance and device navigation remain release evidence |
| 14 - Vocabulary | 14 | 0 | paged filters/exclusions, occurrence drill-down, selected export, source navigation, repository and export tests | Implementation checklist evidenced; large-data and device historical-action validation remain open |
| 15 - Characters | 13 | 0 | virtualized paged grid/list, script/range/priority filters, code-point fallback, selected export, character presentation tests | Implementation checklist evidenced; physical accessibility and render performance remain open |
| 16 - Sessions | 12 | 2 | paged sessions, downsampled timelines, indexed source search, reopen actions, exact deletion preview, tombstones and rollup repair tests | Knownness timeline and correction tools remain open |
| 17 - Goals | 11 | 4 | goal editor, prospective edits, immutable achievements, robust forecast, streak and title-goal tests | Restart-history choice, reminders, compact Today cards, and check-in notes remain open |
| 18 - Anki | 13 | 0 | capability matrix, atomic snapshots, lag reports, missing-item workbenches, cache, freshness controls and Anki analytics tests | Real AnkiDroid provider and large-inventory measurements remain open |
| 19 - Operations | 16 | 2 | versioned portable archive, bounded resumable merge, conflicts, retention, deletion, exports, sync and restore tests | Event compaction and complete maintenance timestamps remain open |
| 20 - Hardening | 20 | 14 | query-plan tests, bounded UI, WorkManager constraints, chart/grid semantics, KMR resources and operations docs | Performance, device accessibility, state/retry, screenshot, and privacy review gates remain open |
| 21 - Rollout | 6 | 4 | rollout v3 preferences, parity export, durable health counters, widget parity, backup authority and rollback importer | Upgrade/release matrix, release note, and actual retirement remain open |

## Open requirement register

These are the 26 unchecked implementation items in `plans/stats.md`.

| ID | Requirement | Why it remains open |
|---|---|---|
| P16-04 | Knownness timeline | Index/snapshot join and render budgets have not passed on representative data/hardware |
| P16-13 | Session correction tools | No narrow, auditable per-session correction workflow exists |
| P17-10 | Prospective or restart-history edits | Edits are explicitly prospective; a restart-history choice is not exposed |
| P17-11 | Opt-in reminders | No goal reminder preference, scheduler, or notification flow exists |
| P17-12 | Compact Today cards | Goals remain in their tab and are not in Overview/widget |
| P17-14 | Manual check-in note | Check-ins persist optional notes, but the current UI submits `null` |
| P19-10 | Finalized event compaction | No measured, semantics-preserving compaction job exists |
| P19-17 | Complete maintenance UI | Size, integrity, conflicts, retention, rebuild and repair exist; last backup/index/rollup timestamps are incomplete |
| P20-01 | Macro/microbenchmarks | Query-plan and bounded-fixture tests are not a benchmark suite |
| P20-02 | Week/year/multi-year profiles | One-year fixtures exist; measured multi-year/100k profiles do not |
| P20-04 | N+1 removal evidence | Some queries are batched, but the complete cover/metadata/Anki path has not been profiled |
| P20-08 | Recorder wake/write validation | Host tests cannot prove device wake, frame, or scroll behavior |
| P20-09 | Database/raw-text growth | No representative device growth measurements exist |
| P20-10 | Storage forecast | A user forecast is blocked until growth estimates are stable |
| P20-A04 | TalkBack validation | Requires a representative Android device |
| P20-A05 | 200% font/display validation | Requires screenshot/manual device coverage |
| P20-A06 | Reduced motion | No complete device audit has been recorded |
| P20-I07 | Translator comments/context | The base resources do not yet carry a complete translator-context audit |
| P20-R01 | Every state audit | Loading/empty/error/stale states exist, but a complete screen-by-screen audit is not recorded |
| P20-R02 | Independent section retry | The primary retry remains a shared refresh for several sections |
| P20-R06 | Recents/screenshot privacy | Title and source-excerpt behavior has not been validated against Android recents |
| P20-R07 | Screenshot tests | No supported light/dark/dynamic-color/tablet/large-font suite exists |
| P21-05 | Multi-version upgrades | Several supported installed-version upgrade paths have not been exercised |
| P21-06 | Release/min-SDK/migration validation | No release build and representative min-SDK device run is recorded |
| P21-08 | Read-only release note | A transition must not be announced before the transition is approved |
| P21-10 | Obsolete code deletion | Must occur only after field evidence, in a separate reviewable change |

## Final acceptance and test matrix

All seven scenarios in section 33 of `plans/stats.md` remain open as
end-to-end Android scenarios: novel, manga/OCR, video, Anki
knownness/maturity, goals, privacy/deletion, and backup/multi-device merge.
Host unit and integration tests cover their underlying invariants but do not
substitute for those runs.

Current test-matrix assessment:

- Pure domain, SQLDelight, repository, capture, indexing, and query behavior
  have broad focused JVM coverage.
- Screen-model coverage is partial; independent retry and every-state coverage
  remain open.
- UI semantics have code-level coverage, while TalkBack, large text, dynamic
  color, tablet, and reduced-motion validation remain open.
- Performance has index-plan and bounded-fixture evidence, not approved device
  latency, battery, memory, or growth budgets.
- Operational merge, tombstone, retention, and export paths have local tests;
  real backup/restore and supported-version upgrades remain open.

## Release and distribution gates

Live evidence checked on 2026-07-27:

- GitHub release `stats-v2.5.0-debug` is still draft and prerelease.
- Its five APK assets each report zero downloads.
- `docs/native-source-manifest.json` has `releaseGate: blocked`.
- Exact corresponding-source evidence for the current FFmpegKit/mpv native
  binaries is still incomplete.

Do not publish those APKs, run a public rollout, disable legacy writes, or
delete legacy code while these gates remain open. Follow
`immersion-stats-legacy-retirement-runbook.md` for staged promotion and
retirement.

## Verification record

Completed on 2026-07-27:

1. focused domain analytics tests: 25 passed;
2. focused SQLDelight data-repository tests: 112 passed;
3. focused app statistics tests: 20 passed;
4. `./gradlew spotlessApply` and `./gradlew spotlessCheck`: passed;
5. `./gradlew assembleDebug`: passed;
6. `git diff --check`, XML validation, and locale-resource scans: passed, with
   no non-base locale resources changed.

Release, device, migration-upgrade, performance, accessibility, distribution,
and retirement gates remain open and unverified.
