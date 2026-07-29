# Upstreaming immersion statistics to Chimahon — character-level scope

Author: prepared 2026-07-29
Target upstream: `sohilsayed/chimahon`, branch `main` (currently `2f648f0a68`)
Source of truth for all existing code: `origin/archive/fork-main-2026-07-29` (`2371e6a57f`)
Merge base with upstream: `a9f7def66b`

---

## 0. Executive summary

The fork contains a complete immersion-statistics subsystem: **78,581 insertions across
471 files** relative to the merge base. This is far too large to land as one pull request,
and roughly 20% of it is scope the user has decided to drop (word-level vocabulary and
tokenization).

This plan does three things:

1. **Cuts the scope** to what was asked for: character-level detail, overview, activity,
   titles, sessions, rollups for speed, and deletion of sessions and titles.
2. **Fixes a real upgrade-breaking defect** discovered while preparing this plan
   (§2, migration numbering). This is not optional polish — shipping without it corrupts
   existing installs.
3. **Sequences the work into 7 reviewable PRs**, each independently
   compilable and testable, so an upstream maintainer can review them one at a time.

Estimated landed size after cuts: **~46,000 insertions** (down from 78,581), of which
~15,000 is tests.

### What is being kept vs dropped

| Area | Decision | Rationale |
|---|---|---|
| Character inventory + occurrences | **Keep** | Explicitly requested. Stored per code point so future features can use it. |
| Overview | **Keep** | Explicitly requested. |
| Activity (heatmap, trends, temporal) | **Keep** | Explicitly requested. |
| Titles | **Keep** | Explicitly requested. |
| Sessions | **Keep** | Explicitly requested. |
| Daily + hourly rollups | **Keep** | Explicitly requested ("rollup etc so its fast"). |
| Session deletion | **Keep** | Explicitly requested. |
| Title deletion | **Keep** | Explicitly requested. |
| **Word/vocabulary tables, UI, analytics** | **Drop** | "tokenisation is a bit extreme". |
| **Tokenizers** (`BoundaryImmersionTokenizer`, `DictionaryBackedJapaneseTokenizer`) | **Drop** | Follows from dropping vocabulary. |
| **Anki maturity inventory** | **Drop from this PR series** | Depends on word identity to join cards to vocabulary. Character-level Anki join is possible later but is not requested. |
| **Goals** | **Drop from this PR series** | Not requested. Self-contained; easy follow-up. |
| **Portable archive export/merge, multi-device sync** | **Drop from this PR series** | Not requested, and the most expensive part to review. |
| **Legacy JSON import** | **Keep, reduced** | Needed so existing users don't appear to lose history (§6.2). |
| Per-character / per-line deletion | **Defer** | User explicitly said "can remain later". |

---

## 1. Why the scope cut is cheap — the code already supports it

This is the single most important architectural finding, and it is what makes this plan
viable rather than a rewrite.

`ImmersionIndexingEngine.process()` in
`domain/src/main/java/tachiyomi/domain/immersion/service/ImmersionIndexing.kt` already
treats character indexing and word tokenization as **separate, sequential, independently
failable stages**. Characters are extracted *first*, and there is already a first-class
terminal state for "characters indexed, no words":

```kotlin
val normalized = normalizer.normalize(rawText, language)
failureCode = CHARACTER_INDEX_FAILURE
val characters = indexCharacters(normalized, item)   // <-- always runs
failureCode = TOKENIZER_FAILURE
val tokenizer = tokenizers.firstOrNull { it.supports(language) }
if (tokenizer == null) {
    repository.storeIndexResult(
        tokenizerId = CHARACTER_ONLY_TOKENIZER_ID,          // <-- already exists
        terminalReason = IndexTerminalReason.UNSUPPORTED_LANGUAGE,
        words = emptyList(),
        characters = characters,                            // <-- characters still persist
    )
    IndexOutcome.UNAVAILABLE
}
```

Consequences:

- Constructing the engine with `tokenizers = emptyList()` yields **exactly** the
  character-only behaviour requested, with no changes to the indexing algorithm.
- `indexCharacters()` has **zero dependency** on any tokenizer, dictionary, or language
  profile. It is pure Unicode code-point iteration gated by
  `DefaultUnicodeCountPolicy.isCountable()`.
- This path is **already under test**. `ImmersionIndexingTest.kt:39`
  (`Unicode inventory counts scalar values across scripts and excludes marks selectors
  punctuation and symbols`) constructs the engine with `FixedTokenizer(emptyList())` and
  asserts the character inventory. That test survives the cut unchanged and becomes the
  primary regression guard.

So dropping vocabulary is **subtractive**, not a redesign. Do not restructure the
indexing engine.

### Vocabulary is a thin slice of the large files

Measured line counts containing vocabulary/tokenization identifiers:

| File | Total lines | Vocabulary-related |
|---|---:|---:|
| `SqlDelightImmersionRepository.kt` | 9,112 | 93 |
| `immersion.sq` | 6,766 | 116 |
| `StatsScreenContent.kt` | 6,234 | 158 |
| `ImmersionAnalyticsService.kt` | 1,553 | 33 |

Roughly 2%. The removal is surgical, not a file-level delete. Budget time for careful
editing of four large files rather than for rearchitecting.

### The UI taxonomy excises cleanly

`StatsScreenState.kt` defines flat enums:

```kotlin
enum class StatsTab { OVERVIEW, ACTIVITY, TITLES, VOCABULARY, CHARACTERS, SESSIONS, GOALS, ANKI }
enum class StatsSection { OVERVIEW, HEATMAP, TRENDS, TEMPORAL_ACTIVITY, TITLE_TRENDS,
                          TITLES, VOCABULARY, VOCABULARY_GROWTH, CHARACTERS,
                          CHARACTER_SUMMARY, SESSIONS, GOALS, ANKI }
```

Delete `VOCABULARY`, `VOCABULARY_GROWTH`, `GOALS`, `ANKI` from these enums and the
compiler will walk you to every consumer. Also delete `NEW_WORDS` from
`StatsTrendMetric`. `StatsFeatureGates.kt` already demonstrates the gating pattern
(`enabledStatsTabs(goalsEnabled, ankiEnabled)`) — after the cut it should collapse to a
plain `StatsTab.entries` or be deleted outright.

---

## 2. BLOCKER: migration numbering collides with upstream and breaks upgrades

**This must be fixed before any PR is opened. It is a data-loss/crash bug, not a style issue.**

### The defect

The fork **overwrote** a migration upstream had already shipped.

- Upstream commit `f8de632756` (2026-07-26) added
  `data/src/main/sqldelight/tachiyomi/migrations/47.sqm`, creating `search_history`.
- The fork's stats work began `823f0744b1` (2026-07-25) and also claimed `47.sqm`,
  filling it with 584 lines of immersion schema.
- Migration `47.sqm` at the merge base already contained upstream's `search_history`
  definition, so the fork's version is a **destructive overwrite** of a released migration,
  not a new file.
- The later sync commit "papered over" the collision by relocating `search_history` to a
  brand-new `63.sqm`.

Verified:

```
upstream 47 creates search_history: 1
fork     47 creates search_history: 0     <-- overwritten
fork     63 creates search_history: 1     <-- relocated
```

### Why this crashes

Consider a user already on upstream's schema 47. They have `search_history`. Upgrading to
a build carrying the fork's migrations:

1. SQLDelight replays migrations **after** their current version, i.e. 48…63.
2. `63.sqm` executes `CREATE TABLE search_history (...)`.
3. The table already exists → `SQLiteException: table search_history already exists` →
   migration fails → **app cannot open its database**.

Meanwhile any user who somehow applied the fork's `47.sqm` never got `search_history` at
all, so upstream's search feature would break for them.

### The fix (mandatory, PR 1)

1. **Restore `47.sqm` byte-for-byte from upstream.** It is a shipped migration and is
   immutable.
   ```bash
   git checkout upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/47.sqm
   ```
2. **Delete the fork's `63.sqm`** entirely. `search_history` is created by upstream's 47;
   re-creating it is the bug.
3. **Renumber the immersion migrations to start after upstream's true tip.** Upstream's
   highest migration is 47, so immersion schema starts at **48**. Because this plan also
   drops vocabulary, Anki, goals, and sync, do **not** port migrations 48–62 one at a
   time. Instead:
4. **Squash the surviving schema into a single new migration.** The fork's 48–62 are an
   archaeological record of its own development (add column, drop trigger, re-add trigger,
   backfill). Upstream users have never seen any of it, so that history has zero value to
   them and is 15 files of review burden. Author **one** `48.sqm` containing the final
   character-scope schema only.

   **This means reading the final shape out of 47 + 48…62 together, not copying 47.**
   Several later migrations add columns and tables that the kept features depend on. At
   minimum, fold in:

   | Source | What it contributes to the squashed 48 |
   |---|---|
   | fork `47.sqm` | Base tables: title, session, source unit, event, exposure, character, character occurrence, daily/lifetime rollup, applied event, rollup state, tombstone, exclusion, retention, import ledger. |
   | `49.sqm`, `51.sqm` | Additional `immersion_source_unit` columns. |
   | `53.sqm` | `immersion_daily_rollup.provenance_state` + `replay_state`; `immersion_lifetime_rollup.replay_state`; **`immersion_event.local_date`** and its index; **`immersion_rollup_dirty` table** — the dirty-range queue PR 4 depends on. |
   | `58.sqm` | Counter/state backfills — fold the *end state* in, drop the `UPDATE` statements (a fresh table needs no backfill). |
   | `60.sqm` | `immersion_hourly_rollup` + indexes. |
   | `61.sqm` | `immersion_title_override`, `immersion_title_mutation`. |
   | `62.sqm` | Title-mutation companion tables. |

   Skip: `48` and `50` (session/lookup columns tied to dropped features), `52` (Anki
   snapshot), `54`/`56`/`57` (FTS4 and its trigger churn), `55` (merge conflict),
   `59` (portable merge checkpoint), `63` (the `search_history` relocation bug).

   Every `INSERT INTO immersion_rollup_dirty (SELECT …)` backfill in 53/60 exists to
   re-enqueue *existing* rows. In a from-scratch migration there are none, so drop those
   statements — but keep the seed `INSERT INTO immersion_rollup_state(...) VALUES
   ('global', 1, 1, 1, 1)` (minus `tokenizer_version`), which the engine reads on startup.
5. **Verify with a real upgrade test**, not by inspection (§7.3).

This is the highest-risk item in the plan and the least visible. Do it first, in its own
PR, and state plainly in the PR description that it corrects a migration collision.

### Resulting schema in the new `48.sqm`

Keep (adapted from the fork's `47.sqm`, `60.sqm`, `61.sqm`, `62.sqm`):

| Table | Purpose |
|---|---|
| `immersion_title` | Title identity, media kind, source key, profile, language. |
| `immersion_session` | Sessions with durations and counters. **Remove `word_count` column.** |
| `immersion_source_unit` | Per-unit provenance, script counters, raw text, indexing status. |
| `immersion_event` | Append-only event log with deltas. **Remove `word_id` FK.** |
| `immersion_source_exposure` | Exposure records with replay ordinal and policy. |
| `immersion_character` | Code point → metadata (name, category, script, readings, grade, JLPT, frequency). |
| `immersion_character_occurrence` | (code point, source unit) → count + first ordinal. |
| `immersion_daily_rollup` | Daily aggregates. **Remove `words`, `unique_words`, `new_words`.** |
| `immersion_hourly_rollup` | Hourly aggregates (from `60.sqm`). |
| `immersion_lifetime_rollup` | Lifetime aggregates. **Remove word columns.** |
| `immersion_applied_event` | Exactly-once rollup application ledger. |
| `immersion_rollup_state` | Version vector. **Remove `tokenizer_version`.** |
| `immersion_rollup_dirty` | Dirty-range queue driving incremental rollup (from `53.sqm`). |
| `immersion_tombstone` | Deletion tombstones (needed for correct session/title deletion). |
| `immersion_exclusion` | Per-entity exclusions. **Character/title scope only.** |
| `immersion_retention_state` | Retention cursor. |
| `immersion_title_override`, `immersion_title_mutation` | Title rename/merge/relink/split (from `61.sqm`). |
| `immersion_import_ledger` | Legacy-import idempotency (§6.2). |

Drop entirely:

`immersion_word`, `immersion_word_occurrence`, `immersion_lookup`,
`immersion_anki_operation`, `immersion_anki_snapshot`, `immersion_anki_item`,
`immersion_goal`, `immersion_goal_check_in`, `immersion_goal_achievement`,
`immersion_sync_peer`, `immersion_merge_conflict`,
`immersion_portable_merge_checkpoint`, `immersion_source_fts`.

On `immersion_source_fts` (the FTS4 virtual table from `54.sqm`, with trigger churn in
`56.sqm`/`57.sqm`): it backs source-text search. It is **not** required for any kept
feature. Drop it from this series and reintroduce later if source search is wanted;
FTS4 triggers plus external-content sync are a meaningful review and correctness cost.

### Column-level cleanups while renumbering

- `immersion_session.word_count` → remove.
- `immersion_source_unit.tokenizer_version` → remove.
  Keep `indexed_version`, `indexing_status`, `index_error_code`, and the script counters
  (`han_characters`, `hiragana_characters`, `katakana_characters`, `hangul_characters`,
  `latin_characters`, `other_characters`, `countable_characters`).
- `immersion_event.word_id` → remove; keep `source_unit_id` **and `local_date`**
  (added by `53.sqm`; the rollup engine buckets on it).
- `immersion_rollup_state.tokenizer_version` → remove.
- Retain `capture_version`, `schema_version`, `normalization_version` — normalization
  still runs and still needs a version for reindexing.

Keep every `CHECK` constraint on surviving columns. They are cheap, they encode the
non-negative-counter invariant, and they caught real bugs during the fork's development.

---

## 3. PR sequence

Seven PRs. Each must compile, pass `spotlessCheck`, and pass its own tests standalone.
Ordering is a strict dependency chain; do not parallelize 1→4.

### PR 1 — Migration correctness and schema foundation

**Scope.** Restore upstream `47.sqm`. Delete fork `63.sqm`. Add a single new `48.sqm`
with the character-scope schema from §2. Add `immersion.sq` queries for the surviving
tables only. No Kotlin behaviour change beyond generated SQLDelight types.

**Files.**
- `data/src/main/sqldelight/tachiyomi/migrations/47.sqm` (restored from upstream)
- `data/src/main/sqldelight/tachiyomi/migrations/48.sqm` (new, ~450 lines)
- `data/src/main/sqldelight/tachiyomi/data/immersion.sq` (new, reduced from 6,766 lines)
- delete `data/src/main/sqldelight/tachiyomi/migrations/63.sqm`

**Tests.** Migration-upgrade test asserting a database at 47 upgrades to 48 with
`search_history` intact (§7.3). This is the single most valuable test in the series.

**Review size.** ~1,200 lines, almost all declarative SQL. Deliberately the smallest PR
because it carries the highest risk.

**Why first.** Everything else depends on the schema, and the maintainer must be able to
review the migration fix without 40,000 lines of Kotlin in the diff.

---

### PR 2 — Domain contracts and value types

**Scope.** Pure-Kotlin domain layer, no Android dependencies, no persistence.

**Files (adapted from `domain/src/main/java/tachiyomi/domain/immersion/`).**

Keep:
- `model/ImmersionTypes.kt` — ids, `LanguageTag`, `MediaKind`, `NonNegativeCounter`,
  `UnicodeCodePoint`.
- `model/ImmersionMetrics.kt` — counters. Remove word metrics.
- `model/ImmersionPersistence.kt` — persistence DTOs. Remove `IndexedWord`.
- `model/SourceLocator.kt` — canonical locators.
- `model/StatsFilter.kt` — filter value type.
- `model/ImmersionTitleIdentity.kt` — stable title identity.
- `model/ImmersionAnalytics.kt` — **reduced**. Delete `VocabularyKnownness`,
  `VocabularyScript`, `VocabularyCategory`, `VocabularyExclusion`, `VocabularyFilter`,
  `AnalyticsWordRow`, `AnalyticsVocabularyFirstSeen*`, `AnalyticsTitleWordAcquisition*`,
  `AnalyticsAnki*`, `AnalyticsGoalProgress`. Keep all `AnalyticsCharacter*`,
  `AnalyticsOverview`, `AnalyticsTrends`, `AnalyticsTemporalActivity`,
  `AnalyticsTitle*` (minus word acquisition), `AnalyticsSessionDetail`, `AnalyticsPage`,
  `AnalyticsResult`, `AnalyticsDataQuality`.
- `model/ImmersionStatsDeletionScope.kt` — **unchanged**. Already enforces the
  non-empty-scope invariant that prevents a blank form from deleting everything.
- `model/ImmersionTitleMaintenance.kt` — rename/merge/relink/split requests + previews.
- `model/AnalyticsCharacterPriorityFormula.kt` — character priority scoring.
- `service/UnicodeCountPolicy.kt` — **critical**, defines what counts as a character.
- `service/ImmersionStatsVersions.kt` — version constants. Remove `TOKENIZER`.
- `service/ImmersionRecorderTime.kt`, `service/ImmersionSessionStateMachine.kt`,
  `service/ImmersionCapturePolicy.kt`, `service/ImmersionAnalyticsCalendar.kt`.
- `repository/ImmersionRepositories.kt` — **reduced** interfaces (§4).

Drop: `service/AnkiInventory.kt`, `model/ImmersionBackup.kt`,
`service/ImmersionExportService.kt`, `service/ImmersionShadowReconciler.kt`,
`service/ImmersionInteractionTelemetry.kt` (lookup telemetry is vocabulary-adjacent),
goal models.

`service/ImmersionIndexing.kt` — **keep**, with tokenizer plumbing removed:
delete `ImmersionToken`, `TokenizationResult`, `ImmersionTokenizer`,
`BoundaryImmersionTokenizer`, `toIndexedWord()`, `ImmersionLexemeNormalizer`. Keep
`NormalizedText`, `SourceTextNormalizer`, `DefaultSourceTextNormalizer`,
`indexCharacters()`, the claim/retry/failure state machine. The `tokenizer == null`
branch becomes the only branch — collapse it and keep `IndexTerminalReason` for
`RAW_TEXT_UNAVAILABLE`.

**Tests.** Port `ImmersionTypesTest`, `ImmersionMetricsTest`, `SourceLocatorTest`,
`ImmersionCharacterAnalyticsTest`, `ImmersionStatsDeletionScopeTest`,
`ImmersionTitleIdentityAdapterTest`, `UnicodeCountPolicyTest`,
`DefaultSourceTextNormalizerTest`, `ImmersionIndexingTest` (minus word cases),
`ImmersionRecorderTimeTest`, `ImmersionSessionStateMachineTest`,
`ImmersionCapturePolicyTest`, `ImmersionAnalyticsCalendarTest`.
Delete `VocabularyFilterTest`, `AnkiInventoryTest`, `ImmersionExportServiceTest`,
`ImmersionShadowReconcilerTest`, `ImmersionInteractionTelemetryTest`.

**Review size.** ~7,000 lines main + ~4,000 tests. Reviewable because it is pure logic
with no I/O and high test density.

---

### PR 3 — Persistence layer

**Scope.** `SqlDelightImmersionRepository` implementing PR 2's interfaces against PR 1's
schema.

**Files.**
- `data/src/main/java/tachiyomi/data/immersion/SqlDelightImmersionRepository.kt`
  — reduced from 9,112 lines. Remove the ~93 word/vocabulary lines plus every Anki,
  goal, portable-archive, and sync method. Expect ~5,500 lines landed.
- Adapters/column mappers for immersion types.

**Tests.** `SqlDelightImmersionRepositoryTest.kt` — reduced from 8,347 lines. This is the
highest-value test file in the whole series: it exercises real SQLite through SQLDelight.
Keep every test for: counter arithmetic, exactly-once event application, rollup
correctness, session deletion, title deletion/mutation, tombstones, deletion previews,
character occurrence aggregation.

**Review size.** ~5,500 main + ~5,000 tests. The largest PR. If the maintainer pushes
back on size, split along the interface seam: `ImmersionRecorderRepository` +
`ImmersionIndexRepository` in 3a, `ImmersionAnalyticsRepository` +
`ImmersionMaintenanceRepository` in 3b.

---

### PR 4 — Rollup engine and background jobs

**Scope.** Incremental aggregation. This is the "so its fast" requirement.

**Files.**
- `domain/.../service/ImmersionAnalyticsService.kt` (reduced, ~1,400 lines)
- `app/src/main/java/mihon/feature/stats/rollup/ImmersionRollupJob.kt`
- `app/src/main/java/mihon/feature/stats/indexing/ImmersionIndexJob.kt`
- `app/src/main/java/mihon/feature/stats/repair/ImmersionRepairJob.kt`
- `app/src/main/java/mihon/feature/stats/retention/ImmersionRetentionJob.kt`
- `app/src/main/java/mihon/feature/stats/indexing/SqlImmersionIndexExclusionPolicy.kt`

Drop: `anki/AnkiInventorySyncJob.kt`, `goals/ImmersionGoalReminderJob.kt`,
`sync/ImmersionStatsSync.kt`, `indexing/DictionaryBackedJapaneseTokenizer.kt`.

**Design to preserve — do not simplify.** The dirty-range queue
(`immersion_rollup_dirty`) plus the applied-event ledger (`immersion_applied_event`) is
what makes rollups both fast and exactly-once. `60.sqm` shows the established pattern:
when the rollup version changes, enqueue affected `(local_date, title_id)` pairs with a
reason string rather than rebuilding everything eagerly.

Register the index job with `tokenizers = emptyList()` (§1). Keep the reindex path — it
is still needed when `normalization_version` changes.

**Tests.** `ImmersionAnalyticsServiceTest.kt` (reduced). Add a test asserting that a
rollup rebuild is idempotent and that replaying an already-applied event does not
double-count.

**Review size.** ~2,500 main + ~1,500 tests.

---

### PR 5 — Capture integration

**Scope.** Wire the recorder into readers. First PR that changes user-visible behaviour.

**Files.**
- `domain/.../service/ImmersionRecorder.kt`, `DefaultImmersionRecorder.kt`
- `domain/.../repository/FeatureFlaggedImmersionRecorderRepository.kt`
- `app/.../mihon/feature/stats/recorder/ImmersionRecorderLifecycleCoordinator.kt`
- `app/.../mihon/feature/stats/capture/MangaCaptureAdapter.kt` (1,103 lines)
- `app/.../mihon/feature/stats/capture/VideoCaptureAdapter.kt` (1,165 lines)
- `app/.../mihon/feature/stats/capture/VideoCaptureLifecycleCoordinator.kt`
- `chimahon/.../stats/capture/NovelCaptureAdapter.kt`
- `domain/.../service/ImmersionStatsPreferences.kt`
- reader/player touchpoints, `KMKDomainModule.kt` registrations

**Feature flags.** `ImmersionStatsPreferences` currently defaults
`captureEnabled = true`, `indexingEnabled = true`, `uiEnabled = false`. For an upstream
PR, **default all three to `false`** and let the maintainer decide the rollout. Capturing
by default while the UI is hidden is defensible in a personal fork but is a poor default
to propose upstream — it writes to the user's database for a feature they cannot see.
Say this explicitly in the PR description rather than leaving it implied.

**Tests.** Capture adapter unit tests (idle timeout, replay/exposure policy,
pause/resume, page revisits).

**Review size.** ~4,000 main + ~2,000 tests. Flag clearly which reader files are touched
and how little changes in each.

---

### PR 6 — Statistics UI

**Scope.** Overview, Activity, Titles, Characters, Sessions tabs.

**Files.**
- `app/.../presentation/more/stats/StatsScreenContent.kt` — 6,234 lines; drop vocabulary,
  goals, Anki sections → expect ~4,200.
- `StatsScreenState.kt` (reduced enums), `StatsTitlesContent.kt`,
  `StatsFilterSelection.kt`, `components/StatsItem.kt`
- `app/.../ui/stats/StatsScreen.kt`, `StatsScreenModel.kt` (2,688 → ~1,900),
  `StatsTitlesScreen.kt`, `StatsCharacterPresentation.kt`,
  `StatsOverviewMetricPresentation.kt`, `StatsComparison.kt`, `StatsDurationParts.kt`,
  `StatsPaging.kt`, `StatsFilterMapping.kt`, `StatsSourceNavigator.kt`,
  `StatsTitleMetadataResolver.kt`, `StatsRecentsPrivacy.kt`, `StatsReaderIdleTimeout.kt`
- `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` — ~590 of 619 `stats_*`
  strings (drop the 31 vocabulary ones and the goal/Anki ones)
- `presentation/more/MoreScreen.kt` — stats entry point

Drop: `StatsAnkiPresentation.kt`, `StatsGoalFactory.kt`,
`StatsGoalForecastPresentation.kt`, `StatsHealthParityExport.kt`.

**Hard constraint.** `StatsScreenContent.kt` at ~4,200 lines is still an unreasonable
review unit. **Split it by tab** as part of this PR: `OverviewSection.kt`,
`ActivitySection.kt`, `TitlesSection.kt`, `CharactersSection.kt`, `SessionsSection.kt`,
with `StatsScreenContent.kt` reduced to routing. This costs a mechanical refactor and
buys a reviewable PR. Do it — a 4,000-line Compose file is the most likely single reason
this PR stalls.

**i18n rule.** Only ever edit `i18n-kmk/src/commonMain/moko-resources/**/base/`. Never
touch non-`base` locale folders — translations come from Weblate. The fork correctly
touched only `base/strings.xml` and `base/plurals.xml`; preserve that.

**Tests.** Port the `app/src/test/.../ui/stats/` suite minus goal/Anki/vocabulary cases.

**Review size.** ~6,000 main + ~1,500 tests, spread across ~8 files after the split.

---

### PR 7 — Deletion, maintenance, and legacy import

**Scope.** The explicitly requested deletion features, plus data continuity.

**Files.**
- `app/.../ui/stats/StatsMaintenanceScreen.kt` (978 lines) + `…ScreenModel.kt`
- `app/.../ui/stats/StatsTitleMaintenanceScreen.kt` (537) + `…ScreenModel.kt`
- `app/.../ui/stats/StatsDeletionScopeInput.kt`
- `app/.../mihon/feature/stats/legacy/LegacyStatsImporter.kt`, `LegacyStatsImportJob.kt`
- `domain/.../interactor/GetLegacyAggregateTotals.kt`

**Deletion surface to land** (all already implemented in
`ImmersionMaintenanceRepository`):

| Capability | Method |
|---|---|
| Delete one session | `deleteSession(sessionId)` |
| Preview session deletion | `previewSessionDeletion(sessionId)` |
| Delete by scope (date/title/media/profile/language) | `deleteScopedStats(scope)` |
| Preview scoped deletion | `previewScopedStatsDeletion(scope)` |
| Delete everything | `resetAllStats()` behind separate confirmation |
| Preview full deletion | `previewAllStatsDeletion()` |
| Delete raw text, keep counters | `deleteRawText()` / `previewRawTextDeletion()` |
| Exclude a title from capture | `setTitleCaptureExcluded()` |
| Unlink / rename / merge / split titles | `unlinkTitle()`, `previewTitleMutation()`, `applyTitleMutation()`, `rollbackTitleMutation()` |
| Integrity check + repair | `validateInvariants()`, `repairSessionCounters()` |

Drop: `exportPortableArchive`, `mergePortableArchive`,
`resolveMergeConflictsKeepingLocal`, `setWordExclusions`.

**Two behaviours worth calling out in the PR description, because both were established
by making tests fail and both look like bugs until explained:**

1. **`resetAllStats` writes tombstones for everything it deletes.** This is deliberate —
   without it, a later merge or import resurrects deleted data. Consequence: deletion is
   not simply "remove rows".
2. **Raw-text deletion preserves counters.** Deleting provenance must not change totals.
   Verified on device: raw-text deletion cleared 2 rows while gross characters stayed at
   20 and sessions at 2.

**Legacy import.** Upstream already stores stats as JSON via
`chimahon/.../data/MangaStatsStorage.kt` and `AnkiStatsStorage.kt`
(`charactersRead`, `readingTime`, keyed by `dateKey` + `mangaId`). Existing users have
real history there. Import it into `immersion_daily_rollup` once, guarded by
`immersion_import_ledger` for idempotency, and mark imported sessions
`legacy_import = 1`. Without this, upgrading users see a statistics screen that reads
zero and will report it as data loss.

Do **not** land the dual-write / legacy-retirement machinery. Import once, read from the
new tables, leave the old JSON files untouched on disk as a passive backup.

**Review size.** ~2,500 main + ~1,000 tests.

---

## 4. Reduced repository interfaces

Target surface after the cut (from
`domain/.../repository/ImmersionRepositories.kt`, currently 478 lines):

```
ImmersionRecorderRepository     — unchanged
ImmersionIndexRepository        — unchanged shape; storeIndexResult loses `words`
ImmersionStatsRepository        — unchanged (overview, sessionsPage, observeRevision)

ImmersionAnalyticsRepository    — keep: availableDateRange, dailyRollups,
                                  inventoryMetrics, bucketInventoryMetrics,
                                  titleInventoryMetrics, titleMetadata, titleNetProgress,
                                  titleUnitProgress, titleCompletedUnits, titleCoverage,
                                  temporalActivity, titleTrendDaily, dataQuality,
                                  characterPage, characterSummary, characterOccurrences,
                                  filteredSessionsPage, sessionDetail, dirtyRollupRanges,
                                  rebuildRollups
                                — drop: vocabularyPage, vocabularyFirstSeenByDate,
                                  wordOccurrences, characterContainingWords,
                                  titleWordAcquisition, sourceSearch, ankiSummary

ImmersionMaintenanceRepository  — keep the deletion/mutation/integrity surface in §PR 7
                                — drop: export/merge portable archive, merge conflicts,
                                  setWordExclusions

ImmersionGoalRepository         — drop entirely
ImmersionAnkiRepository         — drop entirely
```

Note `characterContainingWords` is dropped (it joins characters → words) but
`characterOccurrences` is kept (characters → source units). The character drill-down
therefore shows *where a character appeared*, not *which words contain it*. That is the
correct reduction: it preserves navigation to real reading context without needing word
identity.

---

## 5. What to exclude from every PR

These exist on the archive branch and must **not** appear in any upstream PR:

| Item | Reason |
|---|---|
| `plans/stats.md` (2,837 lines) | Internal working plan. Also: `/docs` and `plans/` are gitignored upstream (`.gitignore:40`), which is why `git add -f` was needed in the fork. |
| `docs/immersion-stats-release-validation.json` / `.md` | Fork-specific release gate; `releaseGate` is `blocked`. |
| `docs/implementation/**`, `docs/architecture/**` | Fork process artifacts. Consider offering `docs/immersion-statistics.md` as user documentation, but as a separate follow-up. |
| `gradle/native-release-compliance.gradle.kts` (689 lines) | Fork's release-compliance validator. Not upstream's concern. |
| `app/src/fork/**`, the `fork` build type in `app/build.gradle.kts` | Side-by-side install variant. Fork-only by definition. |
| `app/src/debug/.../StatsReleaseValidationActivity.kt` | Fork evidence harness. |
| `app/src/androidTest/.../ImmersionStats*Test.kt` (4 files) | Device-evidence harness written for the fork's release gate. Offer separately if wanted. |
| `.github/workflows/*` changes | Fork CI (OAuth validation, submodule init, tag scoping, branch cleanup). |
| `STATS-LICENSE.md`, `NOTICE`, `LICENSES/MIT.txt` changes | Fork licensing bookkeeping. Confirm upstream's preference before touching. |
| `.gemini/config.yaml`, `AGENTS.md` | Fork tooling config. |
| The 14 whitespace-only files from `15b31906a7` | Pure formatting noise (§8). |
| Anki, goals, sync, portable archive, vocabulary | Out of scope per §0. |

---

## 6. Behavioural invariants to preserve

These were established the hard way. Each is load-bearing; do not "simplify" any of them.

1. **Counters never go negative.** `NonNegativeCounter` + SQL `CHECK` constraints.
2. **Events apply exactly once.** `immersion_applied_event` ledger. Replaying an event
   must not double-count.
3. **Deletion is exactly-once and converging.** Verified on device: deleting one of two
   sessions moved sessions 2 → 1 and gross characters 20 → 8; a repeated delete returned
   no preview and left totals unchanged.
4. **Deleting provenance preserves counters** (§PR 7).
5. **Deletion writes tombstones** (§PR 7).
6. **Capability gaps are explicit.** A missing dictionary, absent Anki, or unindexed unit
   must surface as *unavailable* or *partial* — never as a silent zero. This is why
   `IndexTerminalReason` and `indexing_status` exist. After the cut, keep
   `RAW_TEXT_UNAVAILABLE` and the `UNAVAILABLE` outcome.
7. **Session start is atomic.** Fixed in `36ab99b8e7`; keep the transaction boundary.
8. **Idle time is excluded from active duration.** Reader idle timeout is a preference.
9. **Incognito must not write.** The write barrier belongs in the recorder, not the UI.
   Note honestly (§7) that this is unverified end-to-end.
10. **Character counting is Unicode-scalar based**, excludes marks, selectors,
    punctuation, and symbols, and is defined solely by
    `DefaultUnicodeCountPolicy.isCountable()`. Covered by
    `ImmersionIndexingTest.kt:39`.
11. **Rollups are incremental and dirty-range driven** (§PR 4).

---

## 7. Verification plan

### 7.1 Per-PR gates (mandatory, in order)

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew :domain:test :data:test :app:testDebugUnitTest
./gradlew assembleDebug
```

`spotlessApply` before `spotlessCheck` is the project convention. Run `assembleDebug`
last; a clean unit-test run does not imply the app links.

Known repo quirk: **do not combine `clean` with an `assemble` task in one invocation.**
`clean assembleFork` fails with `resource xml/locales_config not found`, and
`clean assemblePreview` fails identically on an untouched variant — it is a pre-existing
ordering issue with the generated `locales_config.xml` (see
`buildSrc/.../LocalesConfigTask.kt`), not a fault of any new code. Run them as separate
invocations.

### 7.2 Baseline before starting

Confirm the *archive* branch builds green in this environment before cutting anything,
so later failures are attributable to the reduction rather than pre-existing state.

### 7.3 Migration upgrade test (PR 1 — highest priority)

The defect in §2 is invisible to unit tests that start from a fresh schema. Write an
instrumented or Robolectric test that:

1. Creates a database at **upstream schema 47** (with `search_history` populated).
2. Applies the new `48.sqm`.
3. Asserts: `search_history` still exists with its rows intact; all immersion tables
   exist; `immersion_rollup_state` has its seed row.
4. Repeats from a **fresh install** (0 → 48) and asserts the same end state.

Also assert no migration file below 48 differs from upstream:

```bash
git diff --exit-code upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/ \
  ':!*/48.sqm'
```

Wire that into CI. It is a one-line guard against ever silently editing a shipped
migration again.

### 7.4 What has actually been verified, and what has not

Be precise in PR descriptions. From five instrumentation runs on 2026-07-28
(API 26 emulator, `debug`, x86_64):

**Verified on device:**
- Repository persistence and Overview query. `OK (1 test)`.
- Scale: 2,500 source units × 40 CJK code points = 100,000 gross characters. Database
  growth **8,024,304 bytes ≈ 3,209 B per source unit**. Timeline returned 120 of 120
  buckets and reconciled exactly with Overview.
- Deletion convergence (scenario 33.6, repository layer).
- Session/title deletion arithmetic and repeated-delete no-op.

**Not verified — state plainly, do not imply otherwise:**
- **All timings.** The host had no `/dev/kvm` and no `vmx`/`svm`; the emulator ran
  `-accel off` (pure software emulation). Every nanosecond figure from those runs is
  unrepresentative. Byte growth is deterministic and *is* meaningful.
- **Character-level end-to-end through the UI.** No run exercised the Characters tab.
- **Incognito write barrier.** Requires driving the reader UI.
- TalkBack, 200% text/display, reduced motion, visual configuration matrix.
- Upgrade/migration on a real device — which is exactly the §2 risk.

Before opening PR 1, run the migration upgrade test on a **real device or a
KVM-accelerated emulator**. It is the one gap that maps directly to a shipping crash.

### 7.5 Manual smoke checklist per PR

- Fresh install → no crash, statistics entry point hidden (flags default off).
- Enable stats → each tab renders, empty state is explicit rather than a bare zero.
- Read manga / novel / video → counters increase; idle time excluded.
- Delete a session → totals drop exactly once; repeat delete is a no-op.
- Delete a title → sessions and source units go; other titles unaffected.
- Rotate device and switch to dark theme on every tab.
- Upgrade over an existing install with legacy JSON stats → history appears, no crash.

---

## 8. Mechanical hygiene

**Formatting noise.** Fork commit `15b31906a7` ("chore: normalize repository formatting")
touched 203 files; 197 still differ at the tip, but only **14 differ solely by
whitespace**. Those 14 are pure noise:

```bash
# identify and revert whitespace-only files before branching
MB=a9f7def66b
for f in $(git diff --name-only $MB origin/archive/fork-main-2026-07-29); do
  git diff --quiet -w $MB origin/archive/fork-main-2026-07-29 -- "$f" \
    && git checkout $MB -- "$f"
done
```

The other 183 contain real changes and must be reviewed individually.

**Fork markers.** The fork wraps additions in `// KMK -->` / `// KMK <--`. That is the
convention for *Komikku carrying downstream patches*. Since this PR targets Chimahon
directly, check with the maintainer whether new first-party code should carry markers at
all; unnecessary markers read as vendored code.

**SPDX headers.** Fork files carry `// SPDX-License-Identifier: MIT`. Upstream files
generally do not. Match upstream's prevailing style and confirm the licensing question
before adding `STATS-LICENSE.md`.

**Commit hygiene.** Author each PR as a small number of logically coherent commits, not
the fork's 69-commit development archaeology. The `fix(stats): harden …` sequence
documents the fork's debugging, not a reviewable narrative.

---

## 9. Ordering, sizing, and risk

| PR | Title | Main LOC | Test LOC | Risk | Blocks |
|---|---|---:|---:|---|---|
| 1 | Migration correctness + schema | ~1,200 | ~300 | **High** | all |
| 2 | Domain contracts | ~7,000 | ~4,000 | Low | 3,4 |
| 3 | Persistence | ~5,500 | ~5,000 | Medium | 4,6,7 |
| 4 | Rollups + jobs | ~2,500 | ~1,500 | Medium | 6 |
| 5 | Capture integration | ~4,000 | ~2,000 | Medium | — |
| 6 | Statistics UI | ~6,000 | ~1,500 | Low | 7 |
| 7 | Deletion + legacy import | ~2,500 | ~1,000 | Medium | — |
|  | **Total** | **~28,700** | **~15,300** | | |

vs. 78,581 insertions on the archive branch — a ~44% reduction, with the removed portion
being entirely out-of-scope features plus fork infrastructure.

**Risk notes.**
- PR 1 is small but carries nearly all the correctness risk. Do not bundle it.
- PR 3 is the largest; have the 3a/3b split ready if the maintainer objects.
- PR 5 changes reader behaviour; ship with flags defaulting off.
- PR 6's success depends on splitting `StatsScreenContent.kt`.

**Talk to the maintainer before writing PR 1.** A 44,000-line feature series is a
significant ask, and the fork *replaces an existing upstream stats screen*
(`StatsScreenModel.kt`, `StatsScreenContent.kt`, `MangaStatsSheet.kt`,
`ReadingStatsWidget.kt`, plus JSON storage in `MangaStatsStorage.kt` /
`AnkiStatsStorage.kt`). That is a product decision, not just a code review. Open an issue
first that: describes the character-level scope, links the archive branch, shows the
3,209 B/source-unit growth measurement, and explicitly flags the migration-47 collision
as a bug found in the fork. Get agreement on direction before investing in PR 1.

---

## 10. Concrete first steps

```bash
# 1. Work from the archive, branch off current upstream
cd /local/home/skerraut/work/chimahon-release-evidence
git fetch upstream --prune
git switch -c stats/character-scope upstream/main

# 2. Restore the shipped migration the fork overwrote (the §2 blocker)
git checkout upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/47.sqm

# 3. Start the new 48.sqm from the fork's base schema, then fold in the later
#    migrations per the table in §2 — copying 47 alone is NOT sufficient.
git show origin/archive/fork-main-2026-07-29:data/src/main/sqldelight/tachiyomi/migrations/47.sqm \
  > data/src/main/sqldelight/tachiyomi/migrations/48.sqm

# review each contributing migration while editing 48.sqm:
for n in 49 51 53 58 60 61 62; do
  echo "===== $n ====="
  git show "origin/archive/fork-main-2026-07-29:data/src/main/sqldelight/tachiyomi/migrations/$n.sqm"
done | less
# hand-edit 48.sqm per §2: drop word/Anki/goal/sync tables and word columns; fold in
# event.local_date + immersion_rollup_dirty (53), hourly rollup (60), title mutation
# (61/62); drop all backfill UPDATE/INSERT-SELECT statements but keep the
# immersion_rollup_state seed row

# 4. Confirm no migration at or below 47 differs from upstream
git diff --exit-code upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/ \
  ':!*/48.sqm' && echo "migrations below 48 are pristine"

# 5. Gates
./gradlew spotlessApply && ./gradlew spotlessCheck
./gradlew :data:test
./gradlew assembleDebug
```

Everything referenced here is on `origin/archive/fork-main-2026-07-29`. Retrieve any
file with:

```bash
git show origin/archive/fork-main-2026-07-29:<path>
```

Useful anchors:
- Character indexing: `domain/src/main/java/tachiyomi/domain/immersion/service/ImmersionIndexing.kt`
- Character-only proof: `domain/src/test/java/tachiyomi/domain/immersion/service/ImmersionIndexingTest.kt:39`
- Schema: `data/src/main/sqldelight/tachiyomi/migrations/47.sqm` (fork version)
- Hourly rollups: `.../migrations/60.sqm`
- Title mutation: `.../migrations/61.sqm`, `62.sqm`
- Deletion surface: `domain/src/main/java/tachiyomi/domain/immersion/repository/ImmersionRepositories.kt:298`
- Deletion scope guard: `domain/src/main/java/tachiyomi/domain/immersion/model/ImmersionStatsDeletionScope.kt`
- UI taxonomy: `app/src/main/java/eu/kanade/presentation/more/stats/StatsScreenState.kt:43` (`StatsTab`)
- Tab gating pattern: `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsFeatureGates.kt`
