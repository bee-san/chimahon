# Immersion statistics completion audit

Audit date: 2026-07-27

Branch: `feat/stats-complete-remaining-plan`

Audited baseline: `08e00b1c3`

## Verdict

The immersion-statistics program is not 100% complete and is not ready for
legacy retirement or public binary distribution.

All phases contain 337 implementation checkboxes. This audit found local code,
documentation, and automated-test evidence where applicable for 323 and left
14 open. A checked roadmap item means only that its implementation is locally
evidenced. Phase definitions of done, the required test matrix, and all seven
final acceptance scenarios still apply independently.

The default rollout remains version 3 shadow mode:

- capture and indexing on;
- UI, Anki inventory, and goals opt-in;
- legacy JSON writes on;
- old files and import compatibility retained.

## Foundation and core-product evidence

The 193 Phase 0-12 checkboxes were delivered in dedicated phase commits but
had remained unchecked in the roadmap. They were re-audited against the live
tree rather than inferred from commit subjects alone.

| Phase | Locally evidenced | Open | Primary evidence |
|---|---:|---:|---|
| 0 - Contracts | 14 | 0 | ADR 0001, domain value types, count policy, lifecycle policy, flags, diagnostics, boundary map, and legacy fixtures |
| 1 - Persistence | 13 | 0 | additive SQLDelight schema/migrations, typed repositories, DI, atomic append/idempotency/integrity/query-plan tests |
| 2 - Legacy import | 13 | 0 | `LegacyStatsImporter`, import ledger, reconciliation/export, defensive fixture and replay tests |
| 3 - Recorder | 17 | 0 | `DefaultImmersionRecorder`, atomic start, bounded queue, lifecycle/time/privacy/shadow diagnostics, recovery and enqueue-budget tests |
| 4 - Novel capture | 14 | 0 | `NovelCaptureAdapter`, WebView range bridge, dual write, reconciliation, source-identity and lifecycle tests |
| 5 - Manga capture | 15 | 0 | page/OCR source split, spread/webtoon visibility, dual write, OCR coverage, capture tests |
| 6 - Video capture | 14 | 0 | playback lifecycle coordinator, subtitle/OCR provenance, replay/coverage/completion handling, capture tests |
| 7 - Lookup and Anki events | 15 | 0 | explicit lookup telemetry, stable Anki operations, repair queue, privacy policy, interaction tests |
| 8 - Indexing | 17 | 0 | Unicode normalizer/indexer, HoshiDicts adapter, claims/retry/reindex/exclusions, split-merge tests |
| 9 - Anki inventory | 16 | 0 | capability probe/configuration, atomic snapshots, maturity matching, WorkManager controls, inventory tests and live checklist |
| 10 - Analytics | 17 | 0 | versioned daily/hourly/lifetime rollups, calendar/filter/data-quality APIs, bounded repair, paging and query-plan tests |
| 11 - Overview | 15 | 0 | independently loadable screen state, full filter model, quality/help UI, widget parity and resilience tests |
| 12 - Trends | 13 | 0 | range/aggregation/metric controls, moving average, bounded title series, temporal rollups, accessible table summaries |

These rows establish implementation coverage, not physical-device performance,
AnkiDroid, playback, accessibility, migration-upgrade, or release evidence.

## Phase 13-21 evidence

| Phase | Locally evidenced | Open | Primary evidence | Phase status |
|---|---:|---:|---|---|
| 13 - Titles | 13 | 0 | `ImmersionTitleIdentity`, `StatsTitleMetadataResolver`, title mutation previews/rollback, title analytics tests | Implementation checklist evidenced; paging performance and device navigation remain release evidence |
| 14 - Vocabulary | 14 | 0 | paged filters/exclusions, occurrence drill-down, selected export, source navigation, repository and export tests | Implementation checklist evidenced; large-data and device historical-action validation remain open |
| 15 - Characters | 13 | 0 | virtualized paged grid/list, script/range/priority filters, code-point fallback, selected export, character presentation tests | Implementation checklist evidenced; physical accessibility and render performance remain open |
| 16 - Sessions | 13 | 1 | paged sessions, downsampled timelines, indexed source search, exact deletion preview, and journaled session-title relink/rollback tests | Knownness timeline remains performance-gated |
| 17 - Goals | 15 | 0 | atomic restart-history edits, opt-in reminders, Today cards/widget progress, private check-in notes, forecasts, streaks, and goal tests | Implementation checklist evidenced; notification/device and end-to-end goal scenarios remain open |
| 18 - Anki | 13 | 0 | capability matrix, atomic snapshots, lag reports, missing-item workbenches, cache, freshness controls and Anki analytics tests | Real AnkiDroid provider and large-inventory measurements remain open |
| 19 - Operations | 18 | 0 | versioned portable archive, bounded resumable merge, conflicts, retention, deletion, exports, sync, restore tests, maintenance timestamps, and retry-safe heartbeat compaction | Implementation checklist evidenced; device storage/growth qualification remains open |
| 20 - Hardening | 25 | 9 | batched metadata/Anki hydration, six-state UI handling, independent retry tests, translator context, recents privacy policy tests, query plans, and bounded UI | Measured performance, device accessibility, growth, and screenshot-suite gates remain open |
| 21 - Rollout | 6 | 4 | rollout v3 preferences, parity export, durable health counters, widget parity, backup authority and rollback importer | Upgrade/release matrix, release note, and actual retirement remain open |

## Open requirement register

These are the 14 unchecked implementation items in `plans/stats.md`.

| ID | Requirement | Why it remains open |
|---|---|---|
| P16-04 | Knownness timeline | The join, UI, and focused repository test exist, but query/render budgets have not passed on representative data/hardware |
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

The twelve requirements closed after the baseline audit have focused local
evidence:

| Requirement | Evidence |
|---|---|
| P16-13 | `RelinkSession` previews and applies one completed session, journals exact rows, blocks unsafe moves, rebuilds rollups, and supports guarded rollback; migration 62 preserves journals with foreign keys enabled and repository tests cover the new mutation type |
| P17-10 | Atomic goal restart archives the prior identity while preserving check-ins and achievements; prospective edit remains available |
| P17-11 | Opt-in WorkManager reminder, KMR notification channel, once-per-local-day guard, generic content, and scheduling tests |
| P17-12 | Compact Today cards in Overview and bounded widget progress derived from the same goal analytics |
| P17-14 | Optional 500-character local note with explicit private-archive disclosure and no implicit raw-text export |
| P19-10 | Bounded retention work compacts only finalized groups of at least three raw heartbeats per five-minute local-time window, preserves active duration, tombstones replaced IDs, marks rollups dirty, skips generated events on retry, and leaves short timelines unchanged |
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

- `./gradlew testReleaseUnitTest` reaches the protected release task graph and
  stops before unit-test execution at
  `:app:verifyAnimatedSceneReleaseReadiness`: "Animated-scene device validation
  is not verified." The required API/device, AnkiDroid, playback, failure, and
  lower-end benchmark matrix remains incomplete.
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
2. full SQLDelight immersion-repository tests: 119 passed, including the
   foreign-key-enabled migration 62 fixture;
3. focused app statistics, goal, widget, privacy, retry, and metadata tests: 34
   passed;
4. `./gradlew testDebugUnitTest`: 859 tests, 854 passed, five
   environment-dependent fixture tests skipped, and none failed;
5. `./gradlew spotlessApply` and `./gradlew spotlessCheck`: passed;
6. `./gradlew assembleDebug`: passed;
7. `git diff --check`, XML validation, and locale-resource scans: passed, with
   no non-base locale resources changed.

Release, device, migration-upgrade, performance, accessibility, distribution,
and retirement gates remain open and unverified.
