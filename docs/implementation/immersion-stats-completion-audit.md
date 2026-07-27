# Immersion statistics completion audit

Audit date: 2026-07-27

Branch: `fix/stats-atomic-session-start`

Audited baseline: `12fa0c219`

## Verdict

The immersion-statistics program is not 100% complete and is not ready for
legacy retirement or public binary distribution.

Phases 13-21 contain 144 implementation checkboxes. This audit found local code
and automated-test evidence for 129 and left 15 open. A checked roadmap item
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
| 16 - Sessions | 13 | 1 | paged sessions, downsampled timelines, indexed source search, exact deletion preview, and journaled session-title relink/rollback tests | Knownness timeline remains performance-gated |
| 17 - Goals | 15 | 0 | atomic restart-history edits, opt-in reminders, Today cards/widget progress, private check-in notes, forecasts, streaks, and goal tests | Implementation checklist evidenced; notification/device and end-to-end goal scenarios remain open |
| 18 - Anki | 13 | 0 | capability matrix, atomic snapshots, lag reports, missing-item workbenches, cache, freshness controls and Anki analytics tests | Real AnkiDroid provider and large-inventory measurements remain open |
| 19 - Operations | 17 | 1 | versioned portable archive, bounded resumable merge, conflicts, retention, deletion, exports, sync, restore tests, and complete maintenance timestamps | Semantics-preserving event compaction remains open |
| 20 - Hardening | 25 | 9 | batched metadata/Anki hydration, six-state UI handling, independent retry tests, translator context, recents privacy policy tests, query plans, and bounded UI | Measured performance, device accessibility, growth, and screenshot-suite gates remain open |
| 21 - Rollout | 6 | 4 | rollout v3 preferences, parity export, durable health counters, widget parity, backup authority and rollback importer | Upgrade/release matrix, release note, and actual retirement remain open |

## Open requirement register

These are the 15 unchecked implementation items in `plans/stats.md`.

| ID | Requirement | Why it remains open |
|---|---|---|
| P16-04 | Knownness timeline | Index/snapshot join and render budgets have not passed on representative data/hardware |
| P19-10 | Finalized event compaction | No measured, semantics-preserving compaction job exists |
| P20-01 | Macro/microbenchmarks | Query-plan and bounded-fixture tests are not a benchmark suite |
| P20-02 | Week/year/multi-year profiles | One-year fixtures exist; measured multi-year/100k profiles do not |
| P20-08 | Recorder wake/write validation | Host tests cannot prove device wake, frame, or scroll behavior |
| P20-09 | Database/raw-text growth | No representative device growth measurements exist |
| P20-10 | Storage forecast | A user forecast is blocked until growth estimates are stable |
| P20-A04 | TalkBack validation | Requires a representative Android device |
| P20-A05 | 200% font/display validation | Requires screenshot/manual device coverage |
| P20-A06 | Reduced motion | No complete device audit has been recorded |
| P20-R07 | Screenshot tests | No supported light/dark/dynamic-color/tablet/large-font suite exists |
| P21-05 | Multi-version upgrades | Several supported installed-version upgrade paths have not been exercised |
| P21-06 | Release/min-SDK/migration validation | No release build and representative min-SDK device run is recorded |
| P21-08 | Read-only release note | A transition must not be announced before the transition is approved |
| P21-10 | Obsolete code deletion | Must occur only after field evidence, in a separate reviewable change |

## Follow-up evidence

The eleven requirements closed after the baseline audit have focused local
evidence:

| Requirement | Evidence |
|---|---|
| P16-13 | `RelinkSession` previews and applies one completed session, journals exact rows, blocks unsafe moves, rebuilds rollups, and supports guarded rollback; migration 62 preserves journals with foreign keys enabled and repository tests cover the new mutation type |
| P17-10 | Atomic goal restart archives the prior identity while preserving check-ins and achievements; prospective edit remains available |
| P17-11 | Opt-in WorkManager reminder, KMR notification channel, once-per-local-day guard, generic content, and scheduling tests |
| P17-12 | Compact Today cards in Overview and bounded widget progress derived from the same goal analytics |
| P17-14 | Optional 500-character local note with explicit private-archive disclosure and no implicit raw-text export |
| P19-17 | Last validated stats backup, repair, successful index, successful rollup, and private-text cleanup are visible; worker/manual success clears stale diagnostic errors only after completion |
| P20-04 | Manga/anime metadata resolves in one query per database; Anki character profiles and per-card character hydration use bounded batch queries with focused call-count/repository tests |
| P20-I07 | Base KMR XML carries context for section retry, widget goals, restart semantics, private check-ins, generic reminders, Android recents, maintenance terms, and session relinking |
| P20-R01 | A six-state collection policy covers loading, empty, content, refreshing content, initial error, and stale error; partial/stale capability labels and maintenance rebuilding states remain explicit |
| P20-R02 | Typed section dispatch retries only the failed generation, preserves stale successful data, and leaves unrelated sections untouched |
| P20-R06 | Android 13+ disables recents screenshots for all stats screens; older APIs secure on pause and restore the global policy on resume; pure controller tests cover adjacent-screen and paused-release behavior |

## Final acceptance and test matrix

All seven scenarios in section 33 of `plans/stats.md` remain open as
end-to-end Android scenarios: novel, manga/OCR, video, Anki
knownness/maturity, goals, privacy/deletion, and backup/multi-device merge.
Host unit and integration tests cover their underlying invariants but do not
substitute for those runs.

Current test-matrix assessment:

- Pure domain, SQLDelight, repository, capture, indexing, and query behavior
  have broad focused JVM coverage.
- Screen-model coverage now includes independent retries and the collection
  state matrix; full Android rendering and interaction remain device work.
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

1. focused domain analytics, diagnostics, and preference tests: 36 passed;
2. full SQLDelight immersion-repository tests: 117 passed, including the
   foreign-key-enabled migration 62 fixture;
3. focused app statistics, goal, widget, privacy, retry, and metadata tests: 34
   passed;
4. `./gradlew spotlessApply` and `./gradlew spotlessCheck`: passed;
5. `./gradlew assembleDebug`: passed;
6. `git diff --check`, XML validation, and locale-resource scans: passed, with
   no non-base locale resources changed.

Release, device, migration-upgrade, performance, accessibility, distribution,
and retirement gates remain open and unverified.
