# Upstreaming immersion statistics to Chimahon — character-level scope

Author: prepared 2026-07-29 (revised 2026-07-29 after scope decision to retain cards,
goals, and portable-archive sync)
Target upstream: `sohilsayed/chimahon`, branch `main` (currently `2f648f0a68`)
Source of truth for all existing code: `origin/archive/fork-main-2026-07-29` (`2371e6a57f`)
Merge base with upstream: `a9f7def66b`

---

## 0. Executive summary

The fork contains a complete immersion-statistics subsystem: **78,581 insertions across
471 files** relative to the merge base. This is far too large to land as one pull request.

This plan does three things:

1. **Cuts one thing only** — word-level vocabulary and tokenization ("tokenisation is a
   bit extreme"). Everything else the fork built is retained: character-level detail,
   overview, activity, titles, sessions, rollups, session/title deletion, **cards
   created**, **goals**, and **portable-archive sync**.
2. **Fixes a real upgrade-breaking defect** discovered while preparing this plan
   (§2, migration numbering). This is not optional polish — shipping without it crashes
   existing installs on upgrade.
3. **Sequences the work into 9 reviewable PRs**, each independently compilable and
   testable.

Estimated landed size: **~66,000 insertions** (down from 78,581), of which ~21,000 is
tests. The reduction comes from removing vocabulary (~4,000), fork infrastructure
(~5,000), and internal process artifacts (~4,000).

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
| **Cards created / updated** | **Keep** | Explicitly requested. Independent of word identity — see §1.2. |
| **Goals** | **Keep** | Explicitly requested. Two word-based metrics dropped — see §1.3. |
| **Portable archive export / merge / multi-device sync** | **Keep** | Explicitly requested. Table-driven, so it adapts to the reduced schema — see §1.4. |
| **One-time legacy JSON import** | **Keep** | Explicitly requested. Import once, no dual-write — see §1.5. |
| **Word/vocabulary tables, UI, analytics** | **Drop** | "tokenisation is a bit extreme". |
| **Tokenizers** (`BoundaryImmersionTokenizer`, `DictionaryBackedJapaneseTokenizer`) | **Drop** | Follows from dropping vocabulary. |
| **Anki word-maturity inventory** | **Drop** | Joins Anki cards to *words*. The character-level Anki join (`immersion_anki_character`) is **kept**. See §1.2. |
| **Lookup telemetry** (`immersion_lookup`) | **Drop** | Records dictionary lookups of words. Vocabulary-adjacent; also the most privacy-sensitive table (stores `raw_query`). |
| **Source-text FTS** (`immersion_source_fts`) | **Drop** | Backs word/source search. Not needed by any kept feature; FTS4 triggers are a large review cost for no retained benefit. |
| Per-character / per-line deletion | **Defer** | User explicitly said "can remain later". |

---

## 1. Feasibility findings — why this scope works

Each subsection below is a verified claim about the existing code, not an assumption.
These are what make the plan cheap rather than a redesign.

### 1.1 Character indexing is already independent of tokenization

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
  character-only behaviour requested, with no change to the indexing algorithm.
- `indexCharacters()` has **zero dependency** on any tokenizer, dictionary, or language
  profile. It is pure Unicode code-point iteration gated by
  `DefaultUnicodeCountPolicy.isCountable()`.
- This path is **already under test**: `ImmersionIndexingTest.kt:39`
  (`Unicode inventory counts scalar values across scripts and excludes marks selectors
  punctuation and symbols`) constructs the engine with `FixedTokenizer(emptyList())` and
  asserts the character inventory. That test survives unchanged and becomes the primary
  regression guard.

Dropping vocabulary is **subtractive**. Do not restructure the indexing engine.

Vocabulary is also a thin slice of the large files:

| File | Total lines | Vocabulary-related |
|---|---:|---:|
| `SqlDelightImmersionRepository.kt` | 9,112 | 93 |
| `immersion.sq` | 6,766 | 116 |
| `StatsScreenContent.kt` | 6,234 | 158 |
| `ImmersionAnalyticsService.kt` | 1,553 | 33 |

Roughly 2%. Budget time for careful editing of four large files, not rearchitecting.

### 1.2 Cards created does NOT depend on word identity

This was the main open risk when retaining cards, and it resolves cleanly.

`CaptureCommand.AnkiOperation` in `ImmersionRecorder.kt:127` carries:

```kotlin
data class AnkiOperation(
    val operationId: AnkiOperationId,
    val sourceUnitId: SourceUnitId?,
    val expressionHash: String,          // <-- identity is a hash of the expression
    val normalizedExpression: String?,
    val normalizedReading: String?,
    val operationType: AnkiOperationType,
    val status: AnkiOperationStatus,
    val noteId: Long?,
    val cardId: Long? = null,
    val deckId: Long? = null,
    val errorCode: String? = null,
) : CaptureCommand
```

**No `word_id`.** Card identity is `expressionHash` + normalized text, and the counters
derive from the operation type alone (`SqlDelightImmersionRepository.kt:2355`):

```kotlin
val cardsCreated = (operation.operationType == AnkiOperationType.CREATE).toLong()
val cardsUpdated = (operation.operationType == AnkiOperationType.UPDATE).toLong()
```

So `immersion_anki_operation`, `session.cards_created`, `session.cards_updated`, and the
`cards_created`/`cards_updated` rollup columns all survive the vocabulary cut untouched.

`immersion_anki_operation.word_id` is a nullable FK used only for word attribution —
drop the column, keep the table.

**What is kept vs dropped within Anki:**

| Table | Decision | Reason |
|---|---|---|
| `immersion_anki_operation` | **Keep** (drop `word_id` column) | Cards created/updated. Identity is `expression_hash`. |
| `immersion_anki_character` | **Keep** | Character ↔ Anki join, added by `52.sqm`. Survives the cut and is genuinely useful: "which kanji do I have cards for". |
| `immersion_anki_snapshot` | **Keep** | Needed for capability state and staleness reporting on the character join. |
| `immersion_anki_item` | **Keep, reduced** | Card inventory. Note `52.sqm` *drops* `character_code_point` and adds word-matching columns (`match_confidence`, `ambiguity_count`). Retain the card fields and the maturity tier; drop `normalized_word`/`normalized_reading`/`match_confidence`/`ambiguity_count` word-matching machinery. |
| Word-maturity aggregation (`AnalyticsAnkiSummary` word tiers) | **Drop** | This is the part that genuinely needs word identity. |

Keep `MaturityTier`, `AnkiMaturityAggregation`, `CapabilityState`, `AnkiInventoryFailure`,
and `AnkiInventory.kt` — maturity applies to the character join too.

Note the honest limitation, already verified on device: the public AnkiDroid provider
reports `noteModificationTime = true` but `cardModificationTime = false` and
`reviewHistory = false`. Maturity is therefore derived from `interval_days`/`queue`, not
from review history. This must surface as a capability limit, never as a silent zero
(§6, invariant 6).

### 1.3 Goals need only two metrics dropped

`ImmersionGoal.metric` is a free-form `String` (`ImmersionPersistence.kt`), so goals have
**no structural dependency on the word table**. The UI's allowlist
(`StatsGoalFactory.kt:16`) is:

```kotlin
internal val STATS_GOAL_METRICS = setOf(
    ACTIVE_TIME_GOAL_METRIC, "gross_characters", "unique_source_characters",
    "net_characters", SOURCE_UNITS_GOAL_METRIC, "sessions", "lookups", "cards",
    "new_words", "new_characters", "manual",
)
```

Remove exactly two entries: **`"new_words"`** (vocabulary) and **`"lookups"`** (lookup
telemetry is dropped). The remaining nine — active time, gross/unique/net characters,
source units, sessions, **cards**, new characters, manual — all work as-is.

`AnalyticsGoalProgress` (pacing, streaks, forecast confidence, rest days) is
metric-agnostic and needs no change. Keep `immersion_goal`,
`immersion_goal_check_in`, `immersion_goal_achievement`, and
`immersion_title_mutation_goal` (from `61.sqm`).

**Migration concern:** a user who created a `new_words` or `lookups` goal on a fork build
would have an orphaned row. Not an upstream concern (upstream users never had these), but
the goal-loading path must tolerate an unknown metric string rather than crash —
`resolveStatsGoal` already returns `null` for `metric !in STATS_GOAL_METRICS`
(`StatsGoalFactory.kt:239`). Verify that null is handled as "hide the goal", not as a
crash, and add a test.

### 1.4 The portable archive is table-driven and adapts automatically

`ImmersionPortableArchive` is a generic table/column/row/cell envelope — it does not
hard-code any schema. The only schema-aware artifact is one declarative list at
`SqlDelightImmersionRepository.kt:8985`:

```kotlin
private val IMMERSION_PORTABLE_TABLES = listOf(
    "immersion_title", "immersion_title_override", "immersion_title_alias",
    "immersion_session", "immersion_session_origin", "immersion_source_unit",
    "immersion_source_origin", "immersion_event", "immersion_source_exposure",
    "immersion_word",                    // <-- remove
    "immersion_character",
    "immersion_word_occurrence",         // <-- remove
    "immersion_character_occurrence",
    "immersion_lookup",                  // <-- remove
    "immersion_anki_operation", "immersion_anki_snapshot", "immersion_anki_item",
    "immersion_anki_character",
    "immersion_goal", "immersion_goal_check_in", "immersion_goal_achievement",
    "immersion_import_ledger", "immersion_sync_peer", "immersion_tombstone",
    "immersion_exclusion", "immersion_retention_state",
)
```

Delete three entries. Also update `IMMERSION_PORTABLE_TABLES`' companion
`IMMERSION_PRIVATE_TEXT_COLUMNS`, which currently redacts
`immersion_lookup` → `raw_query`; that pair goes with the table.

Also update `ImmersionMergeEntityCounts` (`ImmersionBackup.kt:156`): drop the `words` and
`lookups` fields, keep `titles`, `sessions`, `events`, `sourceUnits`, `characters`,
`ankiOperations`, `goals`. And drop the `"WORD"` branch of the tombstone dispatcher
(`SqlDelightImmersionRepository.kt:8831`).

**Bump `formatVersion`.** The archive format is changing (three fewer tables, two fewer
count fields). Increment `ImmersionPortableArchive.formatVersion` and reject archives
whose `formatVersion` is unknown, with a clear message. Do not attempt to merge a
fork-era archive containing `immersion_word` — `mergePortableArchive` already
`require`s every archive table to be in the allowlist
(`SqlDelightImmersionRepository.kt:2992`), so an old archive will fail that check. Make
that failure a *typed, explained* rejection rather than a bare `IllegalArgumentException`.

Keep the merge machinery: checkpoint ledger (`immersion_portable_merge_checkpoint`,
`59.sqm`), merge conflict quarantine (`immersion_merge_conflict`, `55.sqm`),
`immersion_sync_peer`, tombstones, and `ImmersionMergeVerification` (digests, two-pass
rollup comparison, integrity report).

**Three merge behaviours to document in the PR, because each looks like a bug until
explained.** All three were established by making tests fail on device:

1. `resetAllStats` **tombstones everything it deletes**, so simulating a second device by
   resetting locally cannot work — the merge correctly refuses to resurrect tombstoned
   rows.
2. Re-merging a **byte-identical** archive short-circuits on its checkpoint ledger and
   replays the prior report rather than reporting zero inserts. Asserting
   `insertedRows == 0` tests the wrong thing.
3. Re-entering an existing checkpoint reports `RESUMED`, not `ALREADY_COMPLETE`. Testing
   tombstone behaviour requires a *separately exported* archive at a different
   `createdAtEpochMillis`, because an identical archive never reaches the tombstone filter.

Device-verified merge convergence (2026-07-28): device B 15 chars + device A 7 chars →
22 after first merge (8 rows inserted), `ALREADY_COMPLETE` and still 22 on repeat, then
after deleting the merged session (back to 7) merging a separately-exported older copy
reported **5 rows skipped by tombstone** and left totals at 7.

### 1.5 Legacy JSON import: one-time, no dual-write

Upstream already stores stats as JSON via `chimahon/.../data/MangaStatsStorage.kt` and
`AnkiStatsStorage.kt`:

```kotlin
fun addStats(context: Context, characters: Int, timeMs: Long, mangaId: Long = 0,
             date: LocalDate = LocalDate.now())
// MangaStats(dateKey, charactersRead, readingTime, mangaId)
```

Existing users have real history there. Import it **once** into `immersion_daily_rollup`,
guarded by `immersion_import_ledger` (PK `source_key, source_version, content_hash`) for
idempotency, and mark imported sessions `legacy_import = 1`.

Explicitly **do not** land the dual-write / legacy-retirement machinery from the fork.
Import once, read from the new tables afterwards, and leave the old JSON files untouched
on disk as a passive backup. The fork's audit items P21-08/P21-10 (legacy retirement,
read-only release note) require field evidence and belong in a separate later change.

Without this import, upgrading users see a statistics screen reading zero and will report
it as data loss.

### 1.6 The UI taxonomy excises cleanly

`StatsScreenState.kt:43` defines flat enums:

```kotlin
enum class StatsTab { OVERVIEW, ACTIVITY, TITLES, VOCABULARY, CHARACTERS, SESSIONS, GOALS, ANKI }
enum class StatsSection { OVERVIEW, HEATMAP, TRENDS, TEMPORAL_ACTIVITY, TITLE_TRENDS,
                          TITLES, VOCABULARY, VOCABULARY_GROWTH, CHARACTERS,
                          CHARACTER_SUMMARY, SESSIONS, GOALS, ANKI }
```

Delete `VOCABULARY` and `VOCABULARY_GROWTH` only. **`GOALS` and `ANKI` stay.** Also
delete `NEW_WORDS` from `StatsTrendMetric`. The compiler will walk you to every consumer.

`StatsFeatureGates.kt` keeps its shape unchanged — `enabledStatsTabs(goalsEnabled,
ankiEnabled)` is still exactly the right gate now that both features are retained.

---

## 2. BLOCKER: migration numbering collides with upstream and breaks upgrades

**Fix this before opening any PR. It is a crash-on-upgrade bug, not a style issue.**

### The defect

The fork **overwrote a migration upstream had already shipped**.

- Upstream commit `f8de632756` (2026-07-26) added
  `data/src/main/sqldelight/tachiyomi/migrations/47.sqm`, creating `search_history`.
- The fork's stats work began `823f0744b1` (2026-07-25) and also claimed `47.sqm`,
  filling it with 584 lines of immersion schema.
- `47.sqm` already contained upstream's `search_history` at the merge base, so the fork's
  version is a **destructive overwrite of a released migration**, not a new file.
- A later sync commit papered over the collision by relocating `search_history` to a
  brand-new `63.sqm`.

Verified:

```
upstream 47 creates search_history: 1
fork     47 creates search_history: 0     <-- overwritten
fork     63 creates search_history: 1     <-- relocated
```

### Why this crashes

A user on upstream's schema 47 already has `search_history`. Upgrading to a build
carrying the fork's migrations:

1. SQLDelight replays migrations **after** their current version, i.e. 48…63.
2. `63.sqm` executes `CREATE TABLE search_history (...)`.
3. The table already exists → `SQLiteException: table search_history already exists` →
   migration fails → **the app cannot open its database**.

Conversely, any user who applied the fork's `47.sqm` never got `search_history` at all,
so upstream's search feature breaks for them.

### The fix (mandatory, PR 1)

1. **Restore `47.sqm` byte-for-byte from upstream.** A shipped migration is immutable.
   ```bash
   git checkout upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/47.sqm
   ```
2. **Delete the fork's `63.sqm`.** `search_history` is created by upstream's 47;
   re-creating it *is* the bug.
3. **Immersion schema starts at 48**, after upstream's true tip.
4. **Squash the surviving schema into a single new `48.sqm`.** The fork's 48–62 are an
   archaeological record of its own development (add column, drop trigger, re-add
   trigger, backfill). Upstream users have never seen any of it, so that history has zero
   value to them and is 15 files of review burden.

   **Read the final shape out of 47 + 48…62 together — do not just copy 47.** Several
   later migrations add columns and tables that kept features depend on:

   | Source | Contributes to the squashed `48.sqm` |
   |---|---|
   | fork `47.sqm` | Base tables: title, session, source unit, event, exposure, character, character occurrence, daily/lifetime rollup, applied event, rollup state, tombstone, exclusion, retention, import ledger, goal + check-in + achievement, anki operation/snapshot/item, sync peer. |
   | `48.sqm`, `49.sqm`, `50.sqm`, `51.sqm` | Session, source-unit, and lookup column additions. Take the session/source-unit ones; skip lookup columns (table dropped). |
   | **`52.sqm`** | Anki snapshot capability columns (`capability_state`, `provider_version`, `item_count`, `note_count`, `mature_interval_days`, `is_current`, `supports_*`); Anki item card fields; **`immersion_anki_character` table** + index. Note it also *drops* `immersion_anki_item.character_code_point` — so define the final table without it. |
   | `53.sqm` | `immersion_daily_rollup.provenance_state` + `replay_state`; `immersion_lifetime_rollup.replay_state`; **`immersion_event.local_date`** + index; **`immersion_rollup_dirty` table** — the dirty-range queue PR 4 depends on. |
   | **`55.sqm`** | `immersion_merge_conflict` + index (merge quarantine). |
   | `58.sqm` | Counter/state backfills — fold in the *end state*, drop the `UPDATE`s (a fresh table needs no backfill). |
   | **`59.sqm`** | `immersion_portable_merge_checkpoint` + index (merge idempotency). |
   | `60.sqm` | `immersion_hourly_rollup` + indexes. |
   | `61.sqm` | `immersion_title_override`, `immersion_title_mutation` (+2 indexes), `immersion_title_alias`, `immersion_session_origin` (+index), `immersion_title_mutation_session`, `immersion_source_origin` (+index), `immersion_title_mutation_source`, `immersion_title_mutation_goal`. |
   | `62.sqm` | Title-mutation companion tables. |

   **Skip entirely:** `54`, `56`, `57` (FTS4 virtual table and its trigger churn — the
   dropped source search) and `63` (the `search_history` relocation bug).

   Drop every `INSERT INTO immersion_rollup_dirty (SELECT …)` backfill in 53/60 — they
   re-enqueue *existing* rows and a from-scratch migration has none. **Keep** the seed
   `INSERT INTO immersion_rollup_state(...) VALUES ('global', 1, 1, 1, 1)` (minus
   `tokenizer_version`); the engine reads it on startup.

5. **Verify with a real upgrade test** (§7.3), not by inspection.

### Tables in the squashed `48.sqm`

**Keep:**

| Table | Purpose |
|---|---|
| `immersion_title` | Title identity, media kind, source key, profile, language. |
| `immersion_title_override`, `immersion_title_alias` | Rename/relink support. |
| `immersion_title_mutation` (+ `_session`, `_source`, `_goal`) | Rename/merge/relink/split audit + rollback. |
| `immersion_session` | Sessions with durations and counters, incl. `cards_created`, `cards_updated`. **Remove `word_count`.** |
| `immersion_session_origin`, `immersion_source_origin` | Provenance for title mutation. |
| `immersion_source_unit` | Per-unit provenance, script counters, raw text, indexing status. **Remove `tokenizer_version`.** |
| `immersion_event` | Append-only event log with deltas, incl. `local_date`. **Remove `word_id`.** |
| `immersion_source_exposure` | Exposure records with replay ordinal and policy. |
| `immersion_character` | Code point → metadata (name, category, script, readings, grade, JLPT, frequency). |
| `immersion_character_occurrence` | (code point, source unit) → count + first ordinal. |
| `immersion_anki_operation` | Card create/update/duplicate/open/delete. **Remove `word_id`.** |
| `immersion_anki_snapshot` | Snapshot state + capability columns from `52.sqm`. |
| `immersion_anki_item` | Card inventory + maturity tier. Drop word-matching columns. |
| `immersion_anki_character` | Character ↔ Anki join (`52.sqm`). |
| `immersion_goal`, `immersion_goal_check_in`, `immersion_goal_achievement` | Goals, check-ins, milestones. |
| `immersion_daily_rollup` | Daily aggregates incl. `cards_created`/`cards_updated`. **Remove `words`, `unique_words`, `new_words`.** |
| `immersion_hourly_rollup` | Hourly aggregates (`60.sqm`). |
| `immersion_lifetime_rollup` | Lifetime aggregates. **Remove word columns.** |
| `immersion_rollup_dirty` | Dirty-range queue (`53.sqm`). |
| `immersion_applied_event` | Exactly-once rollup application ledger. |
| `immersion_rollup_state` | Version vector. **Remove `tokenizer_version`.** |
| `immersion_portable_merge_checkpoint` | Merge idempotency (`59.sqm`). |
| `immersion_merge_conflict` | Merge quarantine (`55.sqm`). |
| `immersion_sync_peer` | Peer high-water marks. |
| `immersion_tombstone` | Deletion tombstones. |
| `immersion_exclusion` | Per-entity exclusions. **Character/title scope only.** |
| `immersion_retention_state` | Retention cursor. |
| `immersion_import_ledger` | Legacy-import idempotency (§1.5). |

**Drop:** `immersion_word`, `immersion_word_occurrence`, `immersion_lookup`,
`immersion_source_fts`.

### Column-level cleanups

- `immersion_session.word_count` → remove.
- `immersion_source_unit.tokenizer_version` → remove. Keep `indexed_version`,
  `indexing_status`, `index_error_code`, and all script counters (`countable_characters`,
  `han_characters`, `hiragana_characters`, `katakana_characters`, `hangul_characters`,
  `latin_characters`, `other_characters`).
- `immersion_event.word_id` → remove. Keep `source_unit_id`, `anki_operation_id`, and
  `local_date`.
- `immersion_anki_operation.word_id` → remove. Keep `expression_hash`.
- `immersion_anki_item`: keep `note_id`, `card_id`, `note_type_id`, `deck_id`,
  `card_type`, `queue`, `interval_days`, `due`, `lapses`, `ease`, `repetitions`,
  `maturity_tier`, `first_mature_at`, `note_modified_at`, `language_tag`. Drop
  `normalized_word`, `normalized_reading`, `match_confidence`, `ambiguity_count`.
- `immersion_rollup_state.tokenizer_version` → remove. Keep `capture_version`,
  `schema_version`, `normalization_version` — normalization still runs and still needs a
  version for reindexing.
- Rollup tables: drop `words`, `unique_words`, `new_words`. Keep `distinct_characters`,
  `new_characters`, `cards_created`, `cards_updated`, `sessions`, `source_units`,
  character counts, `active_duration_ms`. Drop `lookups`.

Retain every `CHECK` constraint on surviving columns. They encode the
non-negative-counter invariant and caught real bugs during the fork's development.

**Preserve the unique partial index on card identity** — it is what makes card counting
idempotent:

```sql
CREATE UNIQUE INDEX immersion_anki_operation_note_success_index
ON immersion_anki_operation(note_id, type)
WHERE success = 1 AND note_id IS NOT NULL AND type IN ('CREATE', 'UPDATE');
```

---

## 3. PR sequence

Nine PRs. Each must compile, pass `spotlessCheck`, and pass its own tests standalone.
PRs 1→4 are a strict dependency chain; 5, 6, 7 depend on 4; 8 and 9 depend on 6.

### PR 1 — Migration correctness and schema foundation

**Scope.** Restore upstream `47.sqm`. Delete fork `63.sqm`. Add one new `48.sqm` with the
schema from §2. Add `immersion.sq` queries for surviving tables only. No Kotlin
behaviour change beyond generated SQLDelight types.

**Files.**
- `data/src/main/sqldelight/tachiyomi/migrations/47.sqm` (restored from upstream)
- `data/src/main/sqldelight/tachiyomi/migrations/48.sqm` (new, ~750 lines)
- `data/src/main/sqldelight/tachiyomi/data/immersion.sq` (new, reduced from 6,766)
- delete `data/src/main/sqldelight/tachiyomi/migrations/63.sqm`

**Tests.** Migration-upgrade test: a database at 47 upgrades to 48 with `search_history`
intact (§7.3). The single most valuable test in the series.

**Review size.** ~1,800 lines, almost all declarative SQL. Deliberately the smallest PR
because it carries the most risk.

---

### PR 2 — Domain contracts and value types

**Scope.** Pure-Kotlin domain layer. No Android dependencies, no persistence.

**Keep** (from `domain/src/main/java/tachiyomi/domain/immersion/`):
- `model/ImmersionTypes.kt` — ids, `LanguageTag`, `MediaKind`, `NonNegativeCounter`,
  `UnicodeCodePoint`, `EventType`, `AnkiOperationType`, `AnkiOperationStatus`,
  `MaturityTier`, `AnkiSnapshotStatus`, `AnkiInventoryFailure`,
  `AnkiMaturityAggregation`, `CapabilityState`, `ProvenanceState`, `MetricQuality`,
  `RawTextRetention`, `NovelNetProgressPolicy`, `CharacterMetric`, `SessionStatus`,
  `SourceKind`. **Drop `LookupStatus`, `AnkiMatchConfidence`.**
- `model/ImmersionMetrics.kt` — remove word metrics, keep card metrics.
- `model/ImmersionPersistence.kt` — DTOs incl. `ImmersionGoal`, `AnkiOperationEvent`.
  Remove `IndexedWord`.
- `model/ImmersionBackup.kt` — portable archive envelope. Update
  `ImmersionMergeEntityCounts` (§1.4); bump `formatVersion`.
- `model/SourceLocator.kt`, `model/StatsFilter.kt`, `model/ImmersionTitleIdentity.kt`,
  `model/ImmersionTitleMaintenance.kt`, `model/AnalyticsCharacterPriorityFormula.kt`
- `model/ImmersionStatsDeletionScope.kt` — **unchanged**. Already enforces the
  non-empty-scope invariant that stops a blank form deleting everything.
- `model/ImmersionAnalytics.kt` — **reduced**. Delete `VocabularyKnownness`,
  `VocabularyScript`, `VocabularyCategory`, `VocabularyExclusion`, `VocabularyFilter`,
  `AnalyticsWordRow`, `AnalyticsVocabularyFirstSeen*`, `AnalyticsTitleWordAcquisition*`.
  **Keep** all `AnalyticsCharacter*`, `AnalyticsOverview`, `AnalyticsTrends`,
  `AnalyticsTemporalActivity`, `AnalyticsTitle*` (minus word acquisition),
  `AnalyticsSessionDetail`, `AnalyticsPage`, `AnalyticsResult`, `AnalyticsDataQuality`,
  **`AnalyticsGoalProgress`**, **`AnalyticsAnki*`** (minus word-maturity tiers).
- `service/UnicodeCountPolicy.kt` — **critical**; defines what counts as a character.
- `service/ImmersionStatsVersions.kt` — remove `TOKENIZER`.
- `service/AnkiInventory.kt` — **keep** (capability probe + maturity classification).
- `service/ImmersionExportService.kt` — **keep** (portable archive builder).
- `service/ImmersionRecorderTime.kt`, `ImmersionSessionStateMachine.kt`,
  `ImmersionCapturePolicy.kt`, `ImmersionAnalyticsCalendar.kt`,
  `ImmersionStatsDiagnosticsStore.kt`
- `service/ImmersionShadowReconciler.kt` — **keep**; reconciles legacy vs new counters,
  needed by the one-time import (§1.5).
- `interactor/GetLegacyAggregateTotals.kt` — **keep**.
- `repository/ImmersionRepositories.kt` — reduced interfaces (§4).
- `repository/FeatureFlaggedImmersionRecorderRepository.kt`,
  `repository/ImmersionLegacyImportRepository.kt`

**Drop:** `service/ImmersionInteractionTelemetry.kt` (lookup telemetry).

`service/ImmersionIndexing.kt` — **keep**, tokenizer plumbing removed. Delete
`ImmersionToken`, `TokenizationResult`, `ImmersionTokenizer`,
`BoundaryImmersionTokenizer`, `toIndexedWord()`, `ImmersionLexemeNormalizer`. Keep
`NormalizedText`, `SourceTextNormalizer`, `DefaultSourceTextNormalizer`,
`indexCharacters()`, and the claim/retry/failure state machine. The `tokenizer == null`
branch becomes the only branch — collapse it, keeping `IndexTerminalReason` for
`RAW_TEXT_UNAVAILABLE`.

**Tests.** Port `ImmersionTypesTest`, `ImmersionMetricsTest`, `SourceLocatorTest`,
`ImmersionCharacterAnalyticsTest`, `ImmersionStatsDeletionScopeTest`,
`ImmersionTitleIdentityAdapterTest`, `UnicodeCountPolicyTest`,
`DefaultSourceTextNormalizerTest`, `ImmersionIndexingTest` (minus word cases),
`ImmersionRecorderTimeTest`, `ImmersionSessionStateMachineTest`,
`ImmersionCapturePolicyTest`, `ImmersionAnalyticsCalendarTest`, `AnkiInventoryTest`,
`ImmersionExportServiceTest`, `ImmersionShadowReconcilerTest`,
`ImmersionStatsDiagnosticsStoreTest`, `GetLegacyAggregateTotalsTest`.
Delete `VocabularyFilterTest`, `ImmersionInteractionTelemetryTest`.

**Review size.** ~9,000 main + ~5,000 tests. Reviewable: pure logic, no I/O, high test
density.

---

### PR 3 — Persistence layer

**Scope.** `SqlDelightImmersionRepository` implementing PR 2's interfaces against PR 1's
schema.

**Files.**
- `data/src/main/java/tachiyomi/data/immersion/SqlDelightImmersionRepository.kt` —
  reduced from 9,112 lines by removing ~93 word lines, the lookup table, word-maturity
  aggregation, and the `"WORD"` tombstone branch. Expect **~8,300 lines**.
- Column adapters for immersion types.

**Tests.** `SqlDelightImmersionRepositoryTest.kt` — reduced from 8,347. The
highest-value test file in the series: exercises real SQLite through SQLDelight. Keep
every test for counter arithmetic, exactly-once event application, rollup correctness,
session/title deletion, tombstones, deletion previews, character occurrence aggregation,
card idempotency, and archive merge.

**Review size.** ~8,300 main + ~7,500 tests. **The largest PR by far.** Split it up
front along the interface seam rather than waiting for the maintainer to ask:

- **3a** — `ImmersionRecorderRepository` + `ImmersionIndexRepository` + `ImmersionStatsRepository`
- **3b** — `ImmersionAnalyticsRepository` + `ImmersionGoalRepository` + `ImmersionAnkiRepository`
- **3c** — `ImmersionMaintenanceRepository` (deletion, title mutation, portable archive, merge)

3c is the deletion + sync surface and deserves its own review pass.

---

### PR 4 — Rollup engine and background jobs

**Scope.** Incremental aggregation — the "so its fast" requirement.

**Files.**
- `domain/.../service/ImmersionAnalyticsService.kt` (reduced, ~1,500 lines)
- `app/.../mihon/feature/stats/rollup/ImmersionRollupJob.kt`
- `app/.../mihon/feature/stats/indexing/ImmersionIndexJob.kt`
- `app/.../mihon/feature/stats/indexing/SqlImmersionIndexExclusionPolicy.kt`
- `app/.../mihon/feature/stats/repair/ImmersionRepairJob.kt`
- `app/.../mihon/feature/stats/retention/ImmersionRetentionJob.kt`

Drop: `indexing/DictionaryBackedJapaneseTokenizer.kt`.

**Design to preserve — do not simplify.** The dirty-range queue
(`immersion_rollup_dirty`) plus the applied-event ledger (`immersion_applied_event`) is
what makes rollups both fast and exactly-once. `60.sqm` shows the established pattern:
when the rollup version changes, enqueue affected `(local_date, title_id)` pairs with a
reason string rather than rebuilding eagerly.

Register the index job with `tokenizers = emptyList()` (§1.1). Keep the reindex path — it
is still needed when `normalization_version` changes.

**Tests.** `ImmersionAnalyticsServiceTest.kt` (reduced). Add a test asserting a rollup
rebuild is idempotent and that replaying an applied event does not double-count.

**Review size.** ~2,500 main + ~1,500 tests.

---

### PR 5 — Anki inventory sync and card capture

**Scope.** Cards created/updated plus the character↔Anki join. Separated from PR 6 so the
external-provider integration reviews independently.

**Files.**
- `app/.../mihon/feature/stats/anki/AnkiInventorySyncJob.kt`
- Anki capture wiring in `AnkiCardCreator.kt` / `AnkiDroidBridge.kt` (recording
  `CaptureCommand.AnkiOperation` on create/update)
- `chimahon/anki/AnkiDroidInventoryProvider.kt` changes

**Device-verified (2026-07-28), unmocked against AnkiDroid 2.24.0** (APK SHA-256
`b8aaef8c…6989b0`):
- `probe(enabled = true)` → `AVAILABLE`, reported version `2.24.0`.
- `probe(enabled = false)` → `UNAVAILABLE` / `DISABLED` — a disabled integration does not
  degrade to "all unknown".
- Capability limits honest: `noteModificationTime = true`,
  `cardModificationTime = false`, `reviewHistory = false`.

**Not verified:** knownness and maturity against a real collection containing new,
learning, young, and mature cards. Say so plainly; do not imply the acceptance scenario
passed.

**Permission note.** The integration requires
`com.ichi2.anki.permission.READ_WRITE_DATABASE`. It must degrade to `UNAVAILABLE` when
absent or denied, never to zero counts.

**Review size.** ~1,500 main + ~800 tests.

---

### PR 6 — Capture integration

**Scope.** Wire the recorder into readers. First PR that changes user-visible behaviour.

**Files.**
- `domain/.../service/ImmersionRecorder.kt`, `DefaultImmersionRecorder.kt`
- `app/.../mihon/feature/stats/recorder/ImmersionRecorderLifecycleCoordinator.kt`
- `app/.../mihon/feature/stats/capture/MangaCaptureAdapter.kt` (1,103 lines)
- `app/.../mihon/feature/stats/capture/VideoCaptureAdapter.kt` (1,165 lines)
- `app/.../mihon/feature/stats/capture/VideoCaptureLifecycleCoordinator.kt`
- `app/.../mihon/feature/stats/capture/StatsCaptureReconciliationReports.kt`
- `chimahon/.../stats/capture/NovelCaptureAdapter.kt`
- `domain/.../service/ImmersionStatsPreferences.kt`
- reader/player touchpoints, `KMKDomainModule.kt` registrations

**Feature flags.** `ImmersionStatsPreferences` currently defaults `captureEnabled = true`,
`indexingEnabled = true`, `uiEnabled = false`. For upstream, **default all three to
`false`** and let the maintainer choose the rollout. Capturing by default while the UI is
hidden is defensible in a personal fork but a poor default to propose upstream — it
writes to the user's database for a feature they cannot see. State this explicitly in the
PR description.

**Tests.** Capture adapter unit tests: idle timeout, replay/exposure policy,
pause/resume, page revisits, reconciliation.

**Review size.** ~4,000 main + ~2,000 tests. Flag clearly which reader files are touched
and how little changes in each.

---

### PR 7 — Statistics UI

**Scope.** Overview, Activity, Titles, Characters, Sessions, Goals, Anki tabs.

**Files.**
- `app/.../presentation/more/stats/StatsScreenContent.kt` — **must be split, see below**
- `StatsScreenState.kt` (reduced enums), `StatsTitlesContent.kt`,
  `StatsFilterSelection.kt`, `components/StatsItem.kt`, `data/StatsData.kt`
- `app/.../ui/stats/StatsScreen.kt`, `StatsScreenModel.kt` (2,688 → ~2,400),
  `StatsTitlesScreen.kt`, `StatsCharacterPresentation.kt`,
  `StatsOverviewMetricPresentation.kt`, `StatsAnkiPresentation.kt`,
  `StatsGoalFactory.kt`, `StatsGoalForecastPresentation.kt`, `StatsComparison.kt`,
  `StatsDurationParts.kt`, `StatsPaging.kt`, `StatsFilterMapping.kt`,
  `StatsFeatureGates.kt`, `StatsSourceNavigator.kt`, `StatsTitleMetadataResolver.kt`,
  `StatsRecentsPrivacy.kt`, `StatsReaderIdleTimeout.kt`
- `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` — ~588 of 619 `stats_*`
  strings (drop the 31 vocabulary ones)
- `presentation/more/MoreScreen.kt` — stats entry point + preview toggle

Drop: `StatsHealthParityExport.kt` (fork release-evidence export).

**Mandatory file split.** `StatsScreenContent.kt` is 6,234 lines and only ~158 are
vocabulary, so it stays ~6,000 after the cut. A 6,000-line Compose file is the single
most likely reason this PR stalls. **Split by tab as part of this PR:**

```
presentation/more/stats/
├── StatsScreenContent.kt        // routing + shared scaffold only, target <400 lines
├── sections/OverviewSection.kt
├── sections/ActivitySection.kt      // heatmap, trends, temporal
├── sections/TitlesSection.kt        // titles + title trends
├── sections/CharactersSection.kt    // grid, list, summary, drill-down
├── sections/SessionsSection.kt
├── sections/GoalsSection.kt
└── sections/AnkiSection.kt
```

Mirror the existing `StatsSection` enum so the mapping is one-to-one and reviewable.
Keep each file under ~1,000 lines. This is a mechanical move — do it as a **separate
first commit** within PR 7 (pure code movement, no logic change) so the reviewer can diff
the split independently from the vocabulary removal. State in the PR that commit 1 is
move-only.

**i18n rule.** Only ever edit `i18n-kmk/src/commonMain/moko-resources/**/base/`. Never
touch non-`base` locale folders — translations come from Weblate. The fork correctly
touched only `base/strings.xml` and `base/plurals.xml`; preserve that.

**Tests.** Port `app/src/test/.../ui/stats/` minus vocabulary cases. Keep
`StatsGoalFactoryTest`, `StatsGoalForecastTest`, `StatsAnkiPresentationTest`,
`StatsFeatureGatesTest`. Add a test that an unknown goal metric hides the goal rather
than crashing (§1.3).

**Review size.** ~6,500 main + ~1,800 tests, across ~8 files after the split.

---

### PR 8 — Deletion, maintenance, and legacy import

**Scope.** The requested deletion features plus data continuity.

**Files.**
- `app/.../ui/stats/StatsMaintenanceScreen.kt` (978) + `…ScreenModel.kt` (413)
- `app/.../ui/stats/StatsTitleMaintenanceScreen.kt` (537) + `…ScreenModel.kt` (189)
- `app/.../ui/stats/StatsDeletionScopeInput.kt`
- `app/.../mihon/feature/stats/legacy/LegacyStatsImporter.kt`, `LegacyStatsImportJob.kt`

**Deletion surface to land** (all already implemented in
`ImmersionMaintenanceRepository`, `ImmersionRepositories.kt:298`):

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
| Unlink / rename / merge / split titles | `unlinkTitle()`, `previewTitleMutation()`, `applyTitleMutation()`, `rollbackTitleMutation()`, `titleMutations()` |
| Integrity check + repair | `validateInvariants()`, `repairSessionCounters()` |
| Heartbeat compaction | `compactFinalizedHeartbeats()` |

Drop only `setWordExclusions`.

**Two behaviours to call out in the PR description** — both established by making tests
fail, both look like bugs until explained:

1. **`resetAllStats` writes tombstones for everything it deletes.** Deliberate: without
   it, a later merge or import resurrects deleted data. So deletion is not simply
   "remove rows".
2. **Raw-text deletion preserves counters.** Removing provenance must not change totals.
   Device-verified: raw-text deletion cleared 2 rows while gross characters stayed at 20
   and sessions at 2.

**Legacy import** per §1.5: one-time, ledger-guarded, `legacy_import = 1`, no dual-write.

**Review size.** ~2,500 main + ~1,200 tests.

---

### PR 9 — Portable archive export, merge, and multi-device sync

**Scope.** The requested sync feature. Last because it depends on everything and is the
most intricate to review.

**Files.**
- Export/merge UI in settings (data & storage)
- `domain/.../service/ImmersionExportService.kt` wiring (already in PR 2)
- Merge conflict resolution UI
- `IMMERSION_PORTABLE_TABLES` + `IMMERSION_PRIVATE_TEXT_COLUMNS` updates (§1.4)

**Must include:**
- `formatVersion` bump with a typed rejection for unknown versions (§1.4).
- Raw-text opt-in: `includesRawText` on the archive, with
  `IMMERSION_PRIVATE_TEXT_COLUMNS` redaction when false. Raw text is the user's reading
  content — exporting it must be an explicit choice.
- The three counter-intuitive merge behaviours documented in §1.4.
- `ImmersionMergeVerification` (archive digest, two-pass rollup digests, entity counts,
  integrity report) — this is what makes a merge auditable. Keep it.

**Tests.** Merge idempotency, tombstone rejection, conflict quarantine, digest
verification, resumable checkpoint. The device evidence in §1.4 is the model.

**Not verified:** two *physical* devices exchanging real archive files through the
settings UI. The device run modelled the second device with `pm clear` between two
instrumentation phases at the repository layer. State this limitation plainly.

**Review size.** ~2,000 main + ~1,500 tests.

---

## 4. Reduced repository interfaces

Target surface (from `ImmersionRepositories.kt`, currently 478 lines):

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
                                  rebuildRollups, ankiSummary
                                — drop: vocabularyPage, vocabularyFirstSeenByDate,
                                  wordOccurrences, characterContainingWords,
                                  titleWordAcquisition, sourceSearch

ImmersionMaintenanceRepository  — keep the full surface in PR 8 + PR 9
                                  (incl. exportPortableArchive, mergePortableArchive,
                                  resolveMergeConflictsKeepingLocal)
                                — drop: setWordExclusions

ImmersionGoalRepository         — keep unchanged
ImmersionAnkiRepository         — keep, reduced to the character join + card inventory
```

Two deliberate asymmetries:

- `ankiSummary` is **kept** but returns card counts, capability state, and the character
  join — not word-maturity tiers.
- `characterContainingWords` is **dropped** (characters → words) while
  `characterOccurrences` is **kept** (characters → source units). The character
  drill-down therefore shows *where a character appeared*, not *which words contain it*.
  That preserves navigation to real reading context without needing word identity.

---

## 5. What to exclude from every PR

| Item | Reason |
|---|---|
| `plans/stats.md` (2,837 lines), `plans/upstream-stats-character-pr.md` | Internal working plans. `plans/` and `/docs` are gitignored upstream (`.gitignore:40`) — which is why the fork needed `git add -f`. |
| `docs/immersion-stats-release-validation.json` / `.md` | Fork release gate; `releaseGate` is `blocked`. |
| `docs/implementation/**`, `docs/architecture/**` | Fork process artifacts. `docs/immersion-statistics.md` could be offered separately as user documentation. |
| `gradle/native-release-compliance.gradle.kts` (689 lines) | Fork release-compliance validator. |
| `app/src/fork/**`, the `fork` build type in `app/build.gradle.kts` | Side-by-side install variant. Fork-only by definition. |
| `app/src/debug/.../StatsReleaseValidationActivity.kt`, `StatsHealthParityExport.kt` | Fork evidence harness. |
| `app/src/androidTest/.../ImmersionStats*Test.kt` (4 files) | Device-evidence harness written for the fork's release gate. Offer separately if wanted. |
| `.github/workflows/*` changes | Fork CI (OAuth validation, submodule init, tag scoping, branch cleanup). |
| `STATS-LICENSE.md`, `NOTICE`, `LICENSES/MIT.txt` changes | Fork licensing bookkeeping. Confirm upstream's preference first. |
| `.gemini/config.yaml`, `AGENTS.md` | Fork tooling config. |
| The 14 whitespace-only files from `15b31906a7` | Pure formatting noise (§8). |
| Vocabulary, tokenizers, lookup telemetry, source FTS | Out of scope per §0. |

---

## 6. Behavioural invariants to preserve

Each was established the hard way; each is load-bearing. Do not "simplify" any of them.

1. **Counters never go negative.** `NonNegativeCounter` + SQL `CHECK` constraints.
2. **Events apply exactly once.** `immersion_applied_event` ledger; replaying must not
   double-count.
3. **Cards count once per note.** The unique partial index on
   `(note_id, type) WHERE success = 1` is the guard. A repeated create for the same note
   must not inflate `cards_created`.
4. **Deletion is exactly-once and converging.** Device-verified: deleting one of two
   sessions moved sessions 2 → 1 and gross characters 20 → 8; a repeated delete returned
   no preview and left totals unchanged.
5. **Deleting provenance preserves counters** (PR 8).
6. **Deletion writes tombstones**, and merge honours them (PR 8, §1.4).
7. **Capability gaps are explicit.** A missing dictionary, absent or unpermitted
   AnkiDroid, or an unindexed unit must surface as *unavailable* or *partial* — never as a
   silent zero. This is why `IndexTerminalReason`, `indexing_status`, and
   `CapabilityState` exist.
8. **Merge is idempotent and verifiable.** Checkpoint ledger + digest verification;
   conflicts quarantine rather than overwrite.
9. **Session start is atomic.** Fixed in `36ab99b8e7`; keep the transaction boundary.
10. **Idle time is excluded from active duration.** Reader idle timeout is a preference.
11. **Incognito must not write.** The barrier belongs in the recorder, not the UI. Note
    honestly (§7.4) that this is unverified end-to-end.
12. **Character counting is Unicode-scalar based**, excludes marks, selectors,
    punctuation, and symbols, and is defined solely by
    `DefaultUnicodeCountPolicy.isCountable()`. Covered by `ImmersionIndexingTest.kt:39`.
13. **Rollups are incremental and dirty-range driven** (PR 4).
14. **Raw text never leaves the device without explicit opt-in** (PR 9).

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
`clean assemblePreview` fails identically on an untouched variant — a pre-existing
ordering issue with the generated `locales_config.xml` (see
`buildSrc/.../LocalesConfigTask.kt`), not a fault of new code. Run them separately.

### 7.2 Baseline before starting

Confirm the *archive* branch builds green in this environment before cutting anything, so
later failures are attributable to the reduction rather than pre-existing state.

### 7.3 Migration upgrade test (PR 1 — highest priority)

The §2 defect is invisible to unit tests that start from a fresh schema. Write an
instrumented or Robolectric test that:

1. Creates a database at **upstream schema 47**, with `search_history` populated.
2. Applies the new `48.sqm`.
3. Asserts `search_history` still exists with rows intact; all immersion tables exist;
   `immersion_rollup_state` has its seed row.
4. Repeats from a **fresh install** (0 → 48) and asserts the same end state.

Also guard against ever editing a shipped migration again:

```bash
git diff --exit-code upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/ \
  ':!*/48.sqm'
```

Wire that into CI.

### 7.4 What has actually been verified, and what has not

Be precise in PR descriptions. From five instrumentation runs on 2026-07-28
(API 26 emulator, `debug`, x86_64):

**Verified on device:**
- Repository persistence and Overview query. `OK (1 test)`.
- Scale: 2,500 source units × 40 CJK code points = 100,000 gross characters. Database
  growth **8,024,304 bytes ≈ 3,209 B per source unit**. Timeline returned 120 of 120
  buckets and reconciled exactly with Overview.
- Live AnkiDroid 2.24.0 capability probe, unmocked (§PR 5).
- Deletion convergence, scenario 33.6 (repository layer).
- Merge convergence, idempotency, and tombstone rejection, scenario 33.7 (§1.4).

**Not verified — state plainly, do not imply otherwise:**
- **All timings.** The host had no `/dev/kvm` and no `vmx`/`svm`; the emulator ran
  `-accel off` (pure software emulation). Every nanosecond figure from those runs is
  unrepresentative. Byte growth is deterministic and *is* meaningful.
- **Character-level end-to-end through the UI.** No run exercised the Characters tab.
- **Anki maturity against a real collection** with new/learning/young/mature cards.
- **Goals end-to-end.** No run exercised goal creation, pacing, or achievement.
- **Two physical devices** exchanging archive files through the settings UI.
- **Incognito write barrier.** Requires driving the reader UI.
- TalkBack, 200% text/display, reduced motion, visual configuration matrix.
- Upgrade/migration on a real device — exactly the §2 risk.

Before opening PR 1, run the migration upgrade test on a **real device or a
KVM-accelerated emulator**. It is the one gap that maps directly to a shipping crash.

### 7.5 Manual smoke checklist per PR

- Fresh install → no crash; statistics entry point hidden (flags default off).
- Enable stats → every tab renders; empty state is explicit, not a bare zero.
- Read manga / novel / video → counters increase; idle time excluded.
- Create an Anki card from the reader → `cards_created` increments once; repeat for the
  same note → no double count.
- Create a daily goal on characters → progress and pace populate; check-in works.
- Delete a session → totals drop exactly once; repeat delete is a no-op.
- Delete a title → its sessions and source units go; other titles unaffected.
- Export an archive without raw text → confirm no reading content in the file.
- Merge an archive twice → second merge changes nothing.
- Rotate device and switch to dark theme on every tab.
- Upgrade over an existing install with legacy JSON stats → history appears, no crash.
- Uninstall AnkiDroid → Anki tab shows *unavailable*, not zeros.

---

## 8. Mechanical hygiene

**Formatting noise.** Fork commit `15b31906a7` ("chore: normalize repository formatting")
touched 203 files; 197 still differ at the tip, but only **14 differ solely by
whitespace**. Those 14 are pure noise:

```bash
MB=a9f7def66b
for f in $(git diff --name-only $MB origin/archive/fork-main-2026-07-29); do
  git diff --quiet -w $MB origin/archive/fork-main-2026-07-29 -- "$f" \
    && git checkout $MB -- "$f"
done
```

The other 183 contain real changes and must be reviewed individually.

**Fork markers.** The fork wraps additions in `// KMK -->` / `// KMK <--` — the
convention for *Komikku carrying downstream patches*. Since this targets Chimahon
directly, ask the maintainer whether new first-party code should carry markers at all;
unnecessary markers read as vendored code.

**SPDX headers.** Fork files carry `// SPDX-License-Identifier: MIT`; upstream files
generally do not. Match upstream's prevailing style and settle the licensing question
before adding `STATS-LICENSE.md`.

**Commit hygiene.** Author each PR as a small number of logically coherent commits, not
the fork's 69-commit development archaeology. The `fix(stats): harden …` sequence
documents debugging, not a reviewable narrative. The one exception: PR 7's file split
should be its own move-only commit (§PR 7).

---

## 9. Ordering, sizing, and risk

| PR | Title | Main LOC | Test LOC | Risk | Blocks |
|---|---|---:|---:|---|---|
| 1 | Migration correctness + schema | ~1,800 | ~300 | **High** | all |
| 2 | Domain contracts | ~9,000 | ~5,000 | Low | 3,4 |
| 3a | Persistence: recorder, index, stats | ~3,000 | ~2,500 | Medium | 4 |
| 3b | Persistence: analytics, goals, anki | ~2,800 | ~2,500 | Medium | 4,7 |
| 3c | Persistence: maintenance, archive, merge | ~2,500 | ~2,500 | **High** | 8,9 |
| 4 | Rollups + jobs | ~2,500 | ~1,500 | Medium | 7 |
| 5 | Anki inventory + card capture | ~1,500 | ~800 | Medium | 7 |
| 6 | Capture integration | ~4,000 | ~2,000 | Medium | — |
| 7 | Statistics UI (with tab split) | ~6,500 | ~1,800 | Low | 8 |
| 8 | Deletion + legacy import | ~2,500 | ~1,200 | Medium | — |
| 9 | Portable archive + sync | ~2,000 | ~1,500 | **High** | — |
|  | **Total** | **~38,100** | **~21,600** | | |

vs. 78,581 insertions on the archive branch — a ~24% reduction. Smaller than the
character-only variant of this plan (which cut ~44%) because cards, goals, and sync are
retained.

**Risk notes.**
- PR 1 is small but carries nearly all the correctness risk. Do not bundle it.
- PR 3c holds deletion + merge — the two places where a bug destroys user data.
- PR 9's `formatVersion` bump must reject fork-era archives cleanly, not crash.
- PR 6 changes reader behaviour; ship with flags defaulting off.
- PR 7's success depends on the tab split landing as a move-only commit.

**Talk to the maintainer before writing PR 1.** A ~60,000-line feature series is a
significant ask, and this *replaces existing upstream functionality*:
`StatsScreenModel.kt`, `StatsScreenContent.kt`, `MangaStatsSheet.kt`,
`ReadingStatsWidget.kt`, plus JSON storage in `MangaStatsStorage.kt` /
`AnkiStatsStorage.kt`. That is a product decision, not just a code review. Open an issue
first that: describes the scope, links the archive branch, shows the 3,209 B/source-unit
growth measurement, and flags the migration-47 collision as a bug found in the fork. Get
agreement on direction before investing in PR 1.

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

# review each contributing migration while editing 48.sqm
for n in 48 49 50 51 52 53 55 58 59 60 61 62; do
  echo "===== $n ====="
  git show "origin/archive/fork-main-2026-07-29:data/src/main/sqldelight/tachiyomi/migrations/$n.sqm"
done | less
# fold in: anki capability + immersion_anki_character (52); event.local_date +
# immersion_rollup_dirty (53); immersion_merge_conflict (55);
# immersion_portable_merge_checkpoint (59); hourly rollup (60); title mutation (61/62).
# skip 54/56/57 (FTS) and 63 (search_history bug).
# drop word/lookup tables and word columns; drop backfill UPDATE/INSERT-SELECT
# statements; KEEP the immersion_rollup_state seed row.

# 4. Confirm no migration at or below 47 differs from upstream
git diff --exit-code upstream/main -- data/src/main/sqldelight/tachiyomi/migrations/ \
  ':!*/48.sqm' && echo "migrations below 48 are pristine"

# 5. Gates
./gradlew spotlessApply && ./gradlew spotlessCheck
./gradlew :data:test
./gradlew assembleDebug
```

Everything referenced here is on `origin/archive/fork-main-2026-07-29`. Retrieve any file
with:

```bash
git show origin/archive/fork-main-2026-07-29:<path>
```

Useful anchors:
- Character indexing: `domain/src/main/java/tachiyomi/domain/immersion/service/ImmersionIndexing.kt`
- Character-only proof: `domain/src/test/java/tachiyomi/domain/immersion/service/ImmersionIndexingTest.kt:39`
- Card capture command: `domain/src/main/java/tachiyomi/domain/immersion/service/ImmersionRecorder.kt:127`
- Card counter derivation: `data/src/main/java/tachiyomi/data/immersion/SqlDelightImmersionRepository.kt:2355`
- Portable table list: `data/src/main/java/tachiyomi/data/immersion/SqlDelightImmersionRepository.kt:8985`
- Goal metric allowlist: `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsGoalFactory.kt:16`
- Archive envelope + merge report: `domain/src/main/java/tachiyomi/domain/immersion/model/ImmersionBackup.kt`
- Base schema: `data/src/main/sqldelight/tachiyomi/migrations/47.sqm` (fork version)
- Anki + character join: `.../migrations/52.sqm`
- Dirty ranges: `.../migrations/53.sqm`
- Merge conflict / checkpoint: `.../migrations/55.sqm`, `59.sqm`
- Hourly rollups: `.../migrations/60.sqm`
- Title mutation: `.../migrations/61.sqm`, `62.sqm`
- Deletion surface: `domain/src/main/java/tachiyomi/domain/immersion/repository/ImmersionRepositories.kt:298`
- Deletion scope guard: `domain/src/main/java/tachiyomi/domain/immersion/model/ImmersionStatsDeletionScope.kt`
- UI taxonomy: `app/src/main/java/eu/kanade/presentation/more/stats/StatsScreenState.kt:43`
- Tab gating: `app/src/main/java/eu/kanade/tachiyomi/ui/stats/StatsFeatureGates.kt`
